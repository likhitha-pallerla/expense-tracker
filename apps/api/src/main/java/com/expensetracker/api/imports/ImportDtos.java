package com.expensetracker.api.imports;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request and response shapes for CSV import.
 *
 * <p>Import is two steps on purpose. The preview writes nothing, so the user
 * can correct a wrong column guess before any money reaches the ledger.
 */
public final class ImportDtos {

    /** 4 MB of CSV is roughly 40,000 statement lines — far beyond a year. */
    public static final int MAX_CSV_CHARS = 4_000_000;

    private ImportDtos() {
    }

    public record PreviewRequest(
            @NotBlank @Size(max = MAX_CSV_CHARS, message = "That file is too large to import.")
            String csv,
            String filename,
            UUID accountId,
            /** Supplied on a re-preview when the user corrects the guess. */
            ImportMapping mapping) {
    }

    public record CommitRequest(
            @NotBlank @Size(max = MAX_CSV_CHARS, message = "That file is too large to import.")
            String csv,
            String filename,
            @NotNull(message = "Choose which account these transactions belong to.")
            UUID accountId,
            @NotNull(message = "Confirm which column holds what.")
            ImportMapping mapping,
            /** Row numbers the user unticked in the review screen. */
            List<Integer> skipRows) {

        public boolean isSkipped(int rowNumber) {
            return skipRows != null && skipRows.contains(rowNumber);
        }
    }

    /**
     * What an import would do, with nothing written yet.
     *
     * @param mapping   the detected or supplied column mapping
     * @param headers   the file's header row, for the correction dropdowns
     * @param rows      every parsed line, valid or not
     * @param usable    whether the mapping is complete enough to import
     */
    public record Preview(
            ImportMapping mapping,
            List<String> headers,
            List<PreviewRow> rows,
            boolean usable,
            String problem,
            int totalRows,
            int validRows,
            int duplicateRows,
            BigDecimal netAmount) {
    }

    public record PreviewRow(
            int rowNumber,
            Instant occurredAt,
            String description,
            BigDecimal amount,
            String direction,
            String reference,
            String error,
            /** {@code merge}, {@code review}, or null when this row looks new. */
            String duplicateAction,
            Double duplicateScore,
            UUID duplicateOf) {
    }

    /** What an import actually did. */
    public record Result(
            UUID batchId,
            int imported,
            int merged,
            int queuedForReview,
            int skipped,
            int failed) {
    }
}
