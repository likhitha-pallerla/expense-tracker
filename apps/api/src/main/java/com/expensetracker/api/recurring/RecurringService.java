package com.expensetracker.api.recurring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.expensetracker.api.profile.UserSettings;
import com.expensetracker.api.recurring.SeriesDetector.Charge;
import com.expensetracker.api.recurring.SeriesDetector.Series;

/**
 * Subscriptions and other regular payments.
 *
 * <p>Detection runs against the ledger on every read rather than being written
 * down. A stored cadence is wrong the moment a charge is deleted, merged as a
 * duplicate or re-dated, and a subscription page that disagrees with the
 * transaction list is worse than no page at all.
 *
 * <p>What <em>is</em> stored is the user's decisions — that this series is
 * real, what to call it, which category it belongs to, and which suggestions
 * they never want to see again. Those are facts about the user, not about the
 * data, and recomputing them would be presumptuous.
 */
@Service
public class RecurringService {

    /** Long enough for a yearly plan to be charged three times. */
    private static final int LOOKBACK_YEARS = 3;

    /**
     * Everything that could plausibly recur.
     *
     * <p>Transfers are excluded: moving money between your own accounts is
     * regular but is not a payment, and a monthly credit-card settlement would
     * otherwise appear here as a subscription to your own bank. Rows the user
     * excluded from analytics <em>are</em> included — that flag hides a charge
     * from spending totals, it does not stop the money leaving.
     */
    private static final String CHARGES = """
            select coalesce(m.normalized_name, normalize_merchant_name(t.description)) as match_key,
                   coalesce(m.name, t.description) as label,
                   t.direction::text as direction,
                   t.currency,
                   t.account_id,
                   t.category_id,
                   t.occurred_at,
                   t.amount
              from transactions t
              left join merchants m on m.id = t.merchant_id
             where t.user_id = ?
               and t.deleted_at is null
               and t.merged_into_id is null
               and t.kind <> 'transfer'
               and t.occurred_at >= ?
               and coalesce(m.normalized_name, normalize_merchant_name(t.description)) is not null
             order by t.occurred_at
            """;

    private static final String SELECT = """
            select r.id, r.match_key, r.name, r.state::text as state,
                   r.direction::text as direction,
                   r.category_id, c.name as category_name,
                   r.account_id, a.name as account_name,
                   r.amount, r.currency, r.cadence_days,
                   r.first_charged_at, r.last_charged_at, r.next_expected_at,
                   r.occurrences, r.confidence, r.is_subscription, r.is_active, r.notes
              from recurring_transactions r
              left join categories c on c.id = r.category_id
              left join accounts a on a.id = r.account_id
            """;

    private final JdbcTemplate jdbc;
    private final UserSettings settings;

    public RecurringService(JdbcTemplate jdbc, UserSettings settings) {
        this.jdbc = jdbc;
        this.settings = settings;
    }

    // ---- reading -----------------------------------------------------------

    public List<RecurringView> list(UUID userId, boolean includeDismissed) {
        ZoneId zone = settings.zoneOf(userId);
        LocalDate today = settings.today(userId);

        Map<String, Detected> detected = detect(userId, zone, today);
        List<RecurringView> views = new ArrayList<>();
        Set<String> decided = new HashSet<>();

        for (Saved saved : savedRows(userId, null)) {
            if (saved.matchKey() != null) {
                decided.add(saved.matchKey());
            }
            if ("dismissed".equals(saved.state()) && !includeDismissed) {
                continue;
            }
            views.add(fromSaved(saved, detected.get(saved.matchKey()), today, zone));
        }

        for (Detected candidate : detected.values()) {
            if (!decided.contains(candidate.key())) {
                views.add(fromDetected(candidate, today));
            }
        }

        views.sort(ORDER);
        return views;
    }

    public RecurringView get(UUID userId, UUID id) {
        Saved saved = savedRows(userId, id).stream().findFirst().orElseThrow(RecurringService::notFound);
        ZoneId zone = settings.zoneOf(userId);
        LocalDate today = settings.today(userId);
        return fromSaved(saved, detect(userId, zone, today).get(saved.matchKey()), today, zone);
    }

    /**
     * Needing attention first, then whatever is due soonest. A series with no
     * expected date left — nothing to wait for — sinks below one that has,
     * and anything the user has already rejected sinks below everything.
     */
    private static final Comparator<RecurringView> ORDER = Comparator
            .comparingInt((RecurringView v) -> "dismissed".equals(v.state()) ? 1 : 0)
            .thenComparing(v -> v.nextExpected() == null ? LocalDate.MAX : v.nextExpected())
            .thenComparing(RecurringView::name, Comparator.nullsLast(String::compareToIgnoreCase));

    // ---- writing -----------------------------------------------------------

    @Transactional
    public RecurringView create(UUID userId, RecurringRequest request) {
        ZoneId zone = settings.zoneOf(userId);
        LocalDate today = settings.today(userId);

        String requestedName = request.trimmedName();
        String requestedKey = request.trimmedMatchKey();
        if (requestedKey == null && requestedName == null) {
            throw badRequest("Give the payment a name.");
        }

        Map<String, Detected> detected = detect(userId, zone, today);

        // A caller confirming a suggestion sends its key. Anyone entering a
        // payment by hand sends only a name, which is filed under the key that
        // name would normalise to — so the row starts matching real charges as
        // soon as they appear.
        final String key = requestedKey != null
                ? requestedKey
                : keyFor(userId, requestedName, request.directionOrDefault(),
                        request.currencyOrDefault(settings.baseCurrency(userId)));
        final Detected match = detected.get(key);

        final String name = requestedName != null
                ? requestedName
                : (match == null ? null : match.label());
        if (name == null) {
            throw badRequest("Give the payment a name.");
        }

        requireOwned(userId, request);

        Cadence cadence = Cadence.from(request.cadence())
                .orElseGet(() -> match == null ? null : match.series().cadence());
        BigDecimal amount = request.amount() != null
                ? request.amount()
                : (match == null ? null : match.series().latestAmount());

        if (cadence == null || amount == null) {
            throw badRequest("Say how much this costs and how often it is charged.");
        }

        LocalDate nextExpected = request.nextExpected() != null
                ? request.nextExpected()
                : (match == null ? null : match.series().nextExpected());

        UUID id = jdbc.queryForObject("""
                insert into recurring_transactions (
                    user_id, match_key, state, name, direction, category_id, account_id,
                    amount, currency, cadence_days, first_charged_at, last_charged_at,
                    next_expected_at, occurrences, confidence, is_subscription, is_active, notes)
                values (?, ?, 'confirmed'::recurring_state, ?, ?::transaction_direction, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """,
                UUID.class,
                userId,
                key,
                name,
                match != null ? match.direction() : request.directionOrDefault(),
                request.categoryId() != null || match == null
                        ? request.categoryId()
                        : match.categoryId(),
                request.accountId() != null || match == null
                        ? request.accountId()
                        : match.accountId(),
                amount,
                match != null ? match.currency()
                        : request.currencyOrDefault(settings.baseCurrency(userId)),
                cadence.nominalDays(),
                at(match == null ? null : match.series().firstCharge(), zone),
                at(match == null ? null : match.series().lastCharge(), zone),
                at(nextExpected, zone),
                match == null ? 0 : match.series().occurrences(),
                match == null ? null : BigDecimal.valueOf(match.series().confidence()),
                request.subscriptionOrDefault(steady(match)),
                request.activeOrDefault(),
                request.trimmedNotes());

        return get(userId, id);
    }

    @Transactional
    public RecurringView update(UUID userId, UUID id, RecurringRequest request) {
        Saved existing = savedRows(userId, id).stream().findFirst()
                .orElseThrow(RecurringService::notFound);
        requireOwned(userId, request);

        String name = request.trimmedName() == null ? existing.name() : request.trimmedName();
        Cadence cadence = Cadence.from(request.cadence())
                .orElseGet(() -> Cadence.nearest(existing.cadenceDays()));
        BigDecimal amount = request.amount() == null ? existing.amount() : request.amount();

        ZoneId zone = settings.zoneOf(userId);

        jdbc.update("""
                update recurring_transactions
                   set name = ?, category_id = ?, account_id = ?, amount = ?,
                       currency = ?, cadence_days = ?, next_expected_at = coalesce(?, next_expected_at),
                       is_subscription = ?, is_active = ?, notes = ?
                 where id = ? and user_id = ?
                """,
                name,
                request.categoryId(),
                request.accountId(),
                amount,
                request.currencyOrDefault(existing.currency()),
                cadence.nominalDays(),
                at(request.nextExpected(), zone),
                request.isSubscription() == null ? existing.isSubscription() : request.isSubscription(),
                request.activeOrDefault(),
                request.trimmedNotes(),
                id, userId);

        return get(userId, id);
    }

    /**
     * Stop suggesting a series.
     *
     * <p>Update-or-insert, because a user may reject something they once
     * confirmed, and because pressing "not a subscription" twice should not
     * fail the second time.
     */
    @Transactional
    public RecurringView dismiss(UUID userId, String matchKey) {
        if (matchKey == null || matchKey.isBlank()) {
            throw badRequest("Which payment should be dismissed?");
        }
        String key = matchKey.trim();

        Optional<Saved> existing = savedRows(userId, null).stream()
                .filter(s -> key.equals(s.matchKey()))
                .findFirst();

        if (existing.isPresent()) {
            jdbc.update("update recurring_transactions set state = 'dismissed'::recurring_state,"
                    + " is_active = false where id = ? and user_id = ?",
                    existing.get().id(), userId);
            return get(userId, existing.get().id());
        }

        ZoneId zone = settings.zoneOf(userId);
        LocalDate today = settings.today(userId);
        Detected match = detect(userId, zone, today).get(key);
        if (match == null) {
            throw notFound();
        }

        UUID id = jdbc.queryForObject("""
                insert into recurring_transactions (
                    user_id, match_key, state, name, direction, amount, currency,
                    cadence_days, first_charged_at, last_charged_at, next_expected_at,
                    occurrences, confidence, is_subscription, is_active)
                values (?, ?, 'dismissed'::recurring_state, ?, ?::transaction_direction, ?, ?,
                        ?, ?, ?, ?, ?, ?, false, false)
                returning id
                """,
                UUID.class,
                userId, key, match.label(), match.direction(),
                match.series().latestAmount(), match.currency(),
                match.series().cadence().nominalDays(),
                at(match.series().firstCharge(), zone),
                at(match.series().lastCharge(), zone),
                at(match.series().nextExpected(), zone),
                match.series().occurrences(),
                BigDecimal.valueOf(match.series().confidence()));

        return get(userId, id);
    }

    /** Removing the row puts the series back among the suggestions. */
    @Transactional
    public void delete(UUID userId, UUID id) {
        int deleted = jdbc.update(
                "delete from recurring_transactions where id = ? and user_id = ?", id, userId);
        if (deleted == 0) {
            throw notFound();
        }
    }

    // ---- detection ---------------------------------------------------------

    /**
     * A series found in the ledger.
     *
     * @param key merchant, direction and currency together. A refund is not
     *            part of the run of charges that produced it, and two
     *            currencies at one merchant are two different arrangements.
     */
    private record Detected(
            String key, String label, String direction, String currency,
            UUID accountId, UUID categoryId, Series series) {
    }

    private Map<String, Detected> detect(UUID userId, ZoneId zone, LocalDate today) {
        Instant from = today.minusYears(LOOKBACK_YEARS).atStartOfDay(zone).toInstant();

        Map<String, List<ChargeRow>> groups = new LinkedHashMap<>();
        for (ChargeRow row : jdbc.query(CHARGES,
                (rs, n) -> new ChargeRow(
                        rs.getString("match_key"),
                        rs.getString("label"),
                        rs.getString("direction"),
                        rs.getString("currency"),
                        rs.getObject("account_id", UUID.class),
                        rs.getObject("category_id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant().atZone(zone).toLocalDate(),
                        rs.getBigDecimal("amount")),
                userId, Timestamp.from(from))) {
            groups.computeIfAbsent(
                    row.matchKey() + "|" + row.direction() + "|" + row.currency(),
                    k -> new ArrayList<>()).add(row);
        }

        Map<String, Detected> detected = new LinkedHashMap<>();
        for (Map.Entry<String, List<ChargeRow>> entry : groups.entrySet()) {
            List<ChargeRow> rows = entry.getValue();
            if (rows.size() < SeriesDetector.MIN_OCCURRENCES) {
                continue;
            }
            SeriesDetector.detect(rows.stream()
                    .map(r -> new Charge(r.on(), r.amount()))
                    .toList())
                    .ifPresent(series -> {
                        // The newest charge decides the label, account and
                        // category: a merchant that was renamed or a card that
                        // was replaced should show as it is now, not as it was
                        // three years ago.
                        ChargeRow latest = rows.get(rows.size() - 1);
                        detected.put(entry.getKey(), new Detected(
                                entry.getKey(), latest.label(), latest.direction(),
                                latest.currency(), latest.accountId(), latest.categoryId(),
                                series));
                    });
        }
        return detected;
    }

    private record ChargeRow(
            String matchKey, String label, String direction, String currency,
            UUID accountId, UUID categoryId, LocalDate on, BigDecimal amount) {
    }

    // ---- assembling the view ----------------------------------------------

    private RecurringView fromDetected(Detected match, LocalDate today) {
        Series series = match.series();
        Cadence cadence = series.cadence();

        return new RecurringView(
                null,
                match.key(),
                match.label(),
                "suggested",
                statusOf("suggested", true, cadence, series.nextExpected(), today),
                match.direction(),
                match.categoryId(), null,
                match.accountId(), null,
                match.currency(),
                cadence.label(),
                cadence.nominalDays(),
                series.typicalAmount(),
                series.latestAmount(),
                series.amountVaries(),
                series.priceChanged(),
                series.occurrences(),
                series.firstCharge(),
                series.lastCharge(),
                series.nextExpected(),
                daysUntil(today, series.nextExpected()),
                monthlyCost(series.latestAmount(), cadence),
                yearlyCost(series.latestAmount(), cadence),
                steady(match),
                true,
                series.confidence(),
                series.reasons(),
                null);
    }

    /**
     * A saved series, refreshed from the ledger where the ledger has anything
     * to say. The user's own choices — the name, category, notes, whether it is
     * paused — always win; recomputing those would overwrite a decision with a
     * guess.
     */
    private RecurringView fromSaved(Saved saved, Detected match, LocalDate today, ZoneId zone) {
        Series series = match == null ? null : match.series();

        Cadence cadence = series != null
                ? series.cadence()
                : Cadence.nearest(saved.cadenceDays());

        LocalDate nextExpected = series != null
                ? series.nextExpected()
                : date(saved.nextExpectedAt(), zone);

        BigDecimal typical = series != null ? series.typicalAmount() : saved.amount();
        BigDecimal latest = series != null ? series.latestAmount() : saved.amount();

        return new RecurringView(
                saved.id(),
                saved.matchKey(),
                saved.name(),
                saved.state(),
                statusOf(saved.state(), saved.isActive(), cadence, nextExpected, today),
                saved.direction(),
                saved.categoryId(), saved.categoryName(),
                saved.accountId(), saved.accountName(),
                saved.currency(),
                cadence.label(),
                cadence.nominalDays(),
                typical,
                latest,
                series != null && series.amountVaries(),
                series != null && series.priceChanged(),
                series != null ? series.occurrences() : saved.occurrences(),
                series != null ? series.firstCharge() : date(saved.firstChargedAt(), zone),
                series != null ? series.lastCharge() : date(saved.lastChargedAt(), zone),
                nextExpected,
                daysUntil(today, nextExpected),
                monthlyCost(latest, cadence),
                yearlyCost(latest, cadence),
                saved.isSubscription(),
                saved.isActive(),
                series != null ? series.confidence() : 0,
                series != null ? series.reasons() : List.of(),
                saved.notes());
    }

    /**
     * What the money is doing, as opposed to what the user has decided.
     *
     * <p>A charge that has not arrived yet is not late until it is past the
     * drift this cadence normally shows, and it is not gone until a whole extra
     * cycle has come and gone with nothing.
     */
    private String statusOf(String state, boolean isActive, Cadence cadence,
            LocalDate nextExpected, LocalDate today) {
        if ("dismissed".equals(state)) {
            return "dismissed";
        }
        if (!isActive) {
            return "paused";
        }
        if (nextExpected == null) {
            return "active";
        }

        long days = ChronoUnit.DAYS.between(today, nextExpected);
        if (days < 0) {
            long late = -days;
            if (late > cadence.nominalDays() + cadence.toleranceDays()) {
                return "ended";
            }
            return late > cadence.toleranceDays() ? "overdue" : "active";
        }
        if (days == 0) {
            return "due_today";
        }
        return days <= 3 ? "due_soon" : "active";
    }

    /** True for a steady outgoing charge — the kind you can actually cancel. */
    private boolean steady(Detected match) {
        return match != null && !match.series().amountVaries()
                && "debit".equals(match.direction());
    }

    private BigDecimal yearlyCost(BigDecimal amount, Cadence cadence) {
        return amount.multiply(BigDecimal.valueOf(cadence.chargesPerYear()))
                .setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal monthlyCost(BigDecimal amount, Cadence cadence) {
        return yearlyCost(amount, cadence).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
    }

    private Long daysUntil(LocalDate today, LocalDate nextExpected) {
        return nextExpected == null ? null : ChronoUnit.DAYS.between(today, nextExpected);
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * The key a manually entered name would be filed under, so a subscription
     * typed in before its first charge starts matching real charges the moment
     * they arrive. Normalisation happens in Postgres so it cannot drift from
     * the function the detector groups by.
     */
    private String keyFor(UUID userId, String name, String direction, String currency) {
        String normalized = jdbc.queryForObject(
                "select normalize_merchant_name(?)", String.class, name);
        if (normalized == null || normalized.isBlank()) {
            // Nothing survived normalisation — punctuation, or a name in a
            // script it does not handle. Fall back to the raw name so the row
            // still gets a stable key of its own.
            normalized = name.trim().toUpperCase(java.util.Locale.ROOT);
        }
        return normalized + "|" + direction + "|" + currency;
    }

    private void requireOwned(UUID userId, RecurringRequest request) {
        if (request.categoryId() != null) {
            requireExists(userId, "categories", request.categoryId(), "Category not found");
        }
        if (request.accountId() != null) {
            requireExists(userId, "accounts", request.accountId(), "Account not found");
        }
    }

    private void requireExists(UUID userId, String table, UUID id, String message) {
        Long count = jdbc.queryForObject(
                "select count(*) from " + table + " where user_id = ? and id = ?",
                Long.class, userId, id);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
    }

    private List<Saved> savedRows(UUID userId, UUID id) {
        return id == null
                ? jdbc.query(SELECT + " where r.user_id = ?", RecurringService::mapSaved, userId)
                : jdbc.query(SELECT + " where r.user_id = ? and r.id = ?",
                        RecurringService::mapSaved, userId, id);
    }

    private static Timestamp at(LocalDate date, ZoneId zone) {
        return date == null ? null : Timestamp.from(date.atStartOfDay(zone).toInstant());
    }

    private static LocalDate date(Timestamp timestamp, ZoneId zone) {
        return timestamp == null ? null : timestamp.toInstant().atZone(zone).toLocalDate();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurring payment not found");
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record Saved(
            UUID id, String matchKey, String name, String state, String direction,
            UUID categoryId, String categoryName, UUID accountId, String accountName,
            BigDecimal amount, String currency, int cadenceDays,
            Timestamp firstChargedAt, Timestamp lastChargedAt, Timestamp nextExpectedAt,
            int occurrences, boolean isSubscription, boolean isActive, String notes) {
    }

    private static Saved mapSaved(ResultSet rs, int rowNum) throws SQLException {
        return new Saved(
                rs.getObject("id", UUID.class),
                rs.getString("match_key"),
                rs.getString("name"),
                rs.getString("state"),
                rs.getString("direction"),
                rs.getObject("category_id", UUID.class),
                rs.getString("category_name"),
                rs.getObject("account_id", UUID.class),
                rs.getString("account_name"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getInt("cadence_days"),
                rs.getTimestamp("first_charged_at"),
                rs.getTimestamp("last_charged_at"),
                rs.getTimestamp("next_expected_at"),
                rs.getInt("occurrences"),
                rs.getBoolean("is_subscription"),
                rs.getBoolean("is_active"),
                rs.getString("notes"));
    }
}
