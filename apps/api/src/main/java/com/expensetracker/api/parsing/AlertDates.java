package com.expensetracker.api.parsing;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reading the date out of an alert, and knowing when not to believe it.
 *
 * <p>Two things make this harder than it looks. Alerts carry a date but no
 * time, so the instant has to be invented; and {@code 05/06/25} is the fifth of
 * June to an Indian bank and the sixth of May to an American one. This product
 * is India-first, so day-first wins — but a date read the wrong way round can
 * be months away from the truth, which is why every result is checked against
 * when the mail actually arrived.
 */
final class AlertDates {

    /**
     * Day-first formats are tried before anything ambiguous could be read
     * month-first. ISO is unambiguous and safe anywhere in the order.
     */
    private static final List<DateTimeFormatter> FORMATS = List.of(
            pattern("dd-MM-uuuu"),
            pattern("dd/MM/uuuu"),
            pattern("dd-MM-uu"),
            pattern("dd/MM/uu"),
            pattern("uuuu-MM-dd"),
            pattern("dd-MMM-uuuu"),
            pattern("dd-MMM-uu"),
            pattern("dd MMM uuuu"),
            pattern("dd MMM uu"));

    /**
     * How far before the mail a payment may plausibly have happened. Card
     * networks settle over a few days and banks batch overnight, so a small
     * lag is normal; a month is not, and is the signature of a date read the
     * wrong way round.
     */
    private static final int MAX_DAYS_BEFORE = 21;

    /**
     * A payment cannot happen meaningfully after the alert about it. One day of
     * slack absorbs timezone differences between the bank and the user.
     */
    private static final int MAX_DAYS_AFTER = 1;

    private AlertDates() {
    }

    private static DateTimeFormatter pattern(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH);
    }

    /**
     * Turns a date fragment into an instant, falling back to when the mail
     * arrived.
     *
     * <p>The fallback is not a failure: the arrival time of a payment alert is
     * a genuinely good estimate of when the payment happened, usually better
     * than a misread date would be.
     *
     * @param text       the captured fragment, possibly null
     * @param receivedAt when the message arrived, used as both the sanity check
     *                   and the fallback
     * @param zone       the user's timezone, since the fragment carries no time
     */
    static Instant resolve(String text, Instant receivedAt, ZoneId zone) {
        return parse(text, zone)
                .filter(candidate -> plausible(candidate, receivedAt))
                .orElse(receivedAt);
    }

    /** True when the text produced a date we are willing to act on. */
    static boolean trusted(String text, Instant receivedAt, ZoneId zone) {
        return parse(text, zone).filter(candidate -> plausible(candidate, receivedAt)).isPresent();
    }

    private static Optional<Instant> parse(String text, ZoneId zone) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String cleaned = text.strip().replace('.', '-');

        for (DateTimeFormatter format : FORMATS) {
            try {
                LocalDate date = LocalDate.parse(cleaned, format);
                // Midday, not midnight: a date with no time is a whole day, and
                // the middle of it is far enough from either edge that storing
                // it as UTC and reading it back in the user's zone cannot land
                // on the day before or after.
                return Optional.of(date.atTime(LocalTime.NOON).atZone(zone).toInstant());
            } catch (DateTimeParseException ignored) {
                // Try the next shape.
            }
        }
        return Optional.empty();
    }

    private static boolean plausible(Instant candidate, Instant receivedAt) {
        if (receivedAt == null) {
            return true;
        }
        long days = ChronoUnit.DAYS.between(candidate, receivedAt);
        return days <= MAX_DAYS_BEFORE && days >= -MAX_DAYS_AFTER;
    }
}
