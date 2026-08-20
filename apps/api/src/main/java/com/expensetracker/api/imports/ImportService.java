package com.expensetracker.api.imports;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.expensetracker.api.accounts.AccountService;
import com.expensetracker.api.dedup.DedupCandidate;
import com.expensetracker.api.dedup.DedupService;
import com.expensetracker.api.dedup.Provenance;
import com.expensetracker.api.merchants.MerchantNormalizer;
import com.expensetracker.api.transactions.TransactionRequest;
import com.expensetracker.api.transactions.TransactionService;

/**
 * Imports a bank statement CSV.
 *
 * <p>Deliberately two steps: {@link #preview} reads the file and reports what
 * *would* happen without writing anything, and {@link #commit} does the work
 * once the user has confirmed the column mapping. Bank exports vary enough that
 * a silent wrong guess would file real money against the wrong dates.
 */
@Service
public class ImportService {

    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final TransactionService transactions;
    private final DedupService dedup;

    public ImportService(JdbcTemplate jdbc, AccountService accounts,
            TransactionService transactions, DedupService dedup) {
        this.jdbc = jdbc;
        this.accounts = accounts;
        this.transactions = transactions;
        this.dedup = dedup;
    }

    public ImportDtos.Preview preview(UUID userId, ImportDtos.PreviewRequest request) {
        List<List<String>> rows = parse(request.csv());
        if (rows.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That file has no data rows below the header.");
        }

        List<String> headers = rows.get(0);
        List<List<String>> body = rows.subList(1, rows.size());

        ImportMapping mapping = request.mapping() != null
                ? request.mapping()
                : ImportMapping.detect(headers, body);

        if (!mapping.isUsable()) {
            return new ImportDtos.Preview(mapping, headers, List.of(), false,
                    mapping.describeGaps(), body.size(), 0, 0, BigDecimal.ZERO);
        }

        if (request.accountId() != null) {
            accounts.requireOwned(userId, request.accountId());
        }

        // Rows are recorded in the account's own currency, not a global default.
        String currency = request.accountId() == null
                ? "INR"
                : accounts.get(userId, request.accountId()).currency();

        StatementRowReader reader = new StatementRowReader(mapping, zoneOf(userId));
        List<ImportDtos.PreviewRow> preview = new ArrayList<>();
        BigDecimal net = BigDecimal.ZERO;
        int valid = 0;
        int duplicates = 0;

        for (int i = 0; i < body.size(); i++) {
            StatementRowReader.ParsedRow parsed = reader.read(i + 1, body.get(i));

            if (!parsed.isValid()) {
                preview.add(new ImportDtos.PreviewRow(parsed.rowNumber(), null, null, null,
                        null, null, parsed.error(), null, null, null));
                continue;
            }

            valid++;
            net = net.add("debit".equals(parsed.direction())
                    ? parsed.amount().negate()
                    : parsed.amount());

            // The batch id is null here: nothing has been created, and this row
            // is being compared only against what is already stored.
            Optional<DedupService.Assessment> match = dedup.assess(
                    userId, toCandidate(parsed, request.accountId(), currency),
                    new Provenance("csv_import", null));

            if (match.isPresent()) {
                duplicates++;
            }

            preview.add(new ImportDtos.PreviewRow(
                    parsed.rowNumber(),
                    parsed.occurredAt(),
                    parsed.description(),
                    parsed.amount(),
                    parsed.direction(),
                    parsed.reference(),
                    null,
                    match.map(DedupService.Assessment::action).orElse(null),
                    match.map(DedupService.Assessment::score).orElse(null),
                    match.map(DedupService.Assessment::matchesTransactionId).orElse(null)));
        }

        return new ImportDtos.Preview(mapping, headers, preview, true, null,
                body.size(), valid, duplicates, net);
    }

    @Transactional
    public ImportDtos.Result commit(UUID userId, ImportDtos.CommitRequest request) {
        accounts.requireOwned(userId, request.accountId());
        String currency = accounts.get(userId, request.accountId()).currency();

        if (!request.mapping().isUsable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    request.mapping().describeGaps());
        }

        List<List<String>> rows = parse(request.csv());
        if (rows.size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That file has no data rows below the header.");
        }

        List<List<String>> body = rows.subList(1, rows.size());
        UUID batchId = createBatch(userId, request, body.size());

        StatementRowReader reader = new StatementRowReader(request.mapping(), zoneOf(userId));
        int imported = 0;
        int merged = 0;
        int review = 0;
        int skipped = 0;
        int failed = 0;

        for (int i = 0; i < body.size(); i++) {
            StatementRowReader.ParsedRow parsed = reader.read(i + 1, body.get(i));

            if (request.isSkipped(parsed.rowNumber())) {
                skipped++;
                continue;
            }
            if (!parsed.isValid()) {
                failed++;
                continue;
            }

            TransactionService.Created created = transactions.create(
                    userId, toRequest(parsed, request.accountId(), currency), batchId);

            // A merged row folds into an existing transaction rather than
            // standing on its own, so it is not counted twice.
            if (created.dedup().isMerged()) {
                merged++;
            } else {
                imported++;
                if ("review".equals(created.dedup().action())) {
                    review++;
                }
            }
        }

        completeBatch(batchId, imported, merged + review);
        return new ImportDtos.Result(batchId, imported, merged, review, skipped, failed);
    }

    // ---- helpers -----------------------------------------------------------

    private List<List<String>> parse(String csv) {
        try {
            return CsvParser.parse(csv);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private TransactionRequest toRequest(StatementRowReader.ParsedRow row, UUID accountId,
            String currency) {
        boolean credit = "credit".equals(row.direction());
        return new TransactionRequest(
                credit ? "income" : "expense",
                row.direction(),
                row.amount(),
                currency,
                row.occurredAt(),
                row.description(),
                null,
                null,
                accountId,
                null,
                row.description(),
                row.reference(),
                false,
                false);
    }

    private DedupCandidate toCandidate(StatementRowReader.ParsedRow row, UUID accountId,
            String currency) {
        return new DedupCandidate(
                null,
                row.amount(),
                currency,
                row.direction(),
                row.occurredAt(),
                accountId,
                null,
                MerchantNormalizer.normalize(row.description()),
                row.reference(),
                "csv_import");
    }

    private UUID createBatch(UUID userId, ImportDtos.CommitRequest request, int rowCount) {
        return jdbc.queryForObject("""
                insert into import_batches (user_id, filename, account_id, row_count, status)
                values (?, ?, ?, ?, 'running')
                returning id
                """, UUID.class, userId, request.filename(), request.accountId(), rowCount);
    }

    private void completeBatch(UUID batchId, int imported, int duplicates) {
        jdbc.update("""
                update import_batches
                   set imported_count = ?, duplicate_count = ?,
                       status = 'completed', completed_at = now()
                 where id = ?
                """, imported, duplicates, batchId);
    }

    /**
     * A statement date has no time, so it is anchored in the user's own zone —
     * otherwise a purchase could land on the previous day in their ledger.
     */
    private ZoneId zoneOf(UUID userId) {
        String timezone = jdbc.query(
                "select timezone from profiles where id = ?",
                rs -> rs.next() ? rs.getString(1) : null, userId);

        try {
            return timezone == null ? ZoneId.of("Asia/Kolkata") : ZoneId.of(timezone);
        } catch (Exception ex) {
            return ZoneId.of("Asia/Kolkata");
        }
    }
}
