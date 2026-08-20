package com.expensetracker.api.imports;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns one raw CSV row into something that can become a transaction.
 *
 * <p>A row that cannot be read is reported rather than skipped silently, so an
 * import never quietly loses lines the user believed were included.
 */
public final class StatementRowReader {

    private final ImportMapping mapping;
    private final ZoneId zone;

    public StatementRowReader(ImportMapping mapping, ZoneId zone) {
        this.mapping = mapping;
        this.zone = zone;
    }

    public ParsedRow read(int rowNumber, List<String> row) {
        Optional<LocalDate> date =
                StatementValues.parseDate(cell(row, mapping.dateColumn()), mapping.dayFirst());
        if (date.isEmpty()) {
            return ParsedRow.failed(rowNumber, "Could not read a date from '"
                    + cell(row, mapping.dateColumn()) + "'.");
        }

        Optional<Signed> signed = readAmount(row);
        if (signed.isEmpty()) {
            return ParsedRow.failed(rowNumber, "Could not read an amount.");
        }

        BigDecimal amount = signed.get().amount();
        if (amount.signum() == 0) {
            return ParsedRow.failed(rowNumber, "Amount is zero.");
        }

        String description = cell(row, mapping.descriptionColumn());
        String reference = cell(row, mapping.referenceColumn());

        // A date with no time is placed at midday so that no timezone shift can
        // move it onto the day before or after.
        Instant occurredAt = date.get().atTime(12, 0).atZone(zone).toInstant();

        return new ParsedRow(
                rowNumber,
                occurredAt,
                date.get(),
                description.isBlank() ? null : description,
                amount.abs(),
                signed.get().credit() ? "credit" : "debit",
                reference.isBlank() ? null : reference,
                null);
    }

    /**
     * Reads the amount from whichever layout the bank used.
     *
     * <p>With split withdrawal/deposit columns the populated one gives both the
     * value and the direction. With a single column the sign does.
     */
    private Optional<Signed> readAmount(List<String> row) {
        if (mapping.debitColumn() >= 0 || mapping.creditColumn() >= 0) {
            Optional<BigDecimal> debit = StatementValues.parseAmount(cell(row, mapping.debitColumn()));
            Optional<BigDecimal> credit = StatementValues.parseAmount(cell(row, mapping.creditColumn()));

            // Banks write 0.00 rather than a blank in the unused column.
            if (debit.filter(v -> v.signum() != 0).isPresent()) {
                return Optional.of(new Signed(debit.get().abs(), false));
            }
            if (credit.filter(v -> v.signum() != 0).isPresent()) {
                return Optional.of(new Signed(credit.get().abs(), true));
            }
            return Optional.empty();
        }

        return StatementValues.parseAmount(cell(row, mapping.amountColumn()))
                .map(value -> new Signed(value.abs(),
                        indicated(row).orElseGet(() -> value.signum() > 0)));
    }

    /**
     * Reads a separate DR/CR column when the bank uses one.
     *
     * <p>Those statements list every amount unsigned, so without this a whole
     * file of withdrawals would import as income.
     *
     * @return true for credit, false for debit, empty when there is no such column
     */
    private Optional<Boolean> indicated(List<String> row) {
        String value = cell(row, mapping.typeColumn()).trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (value.startsWith("cr") || value.equals("c") || value.contains("deposit")
                || value.contains("credit")) {
            return Optional.of(true);
        }
        if (value.startsWith("dr") || value.equals("d") || value.contains("withdraw")
                || value.contains("debit")) {
            return Optional.of(false);
        }
        return Optional.empty();
    }

    private static String cell(List<String> row, int index) {
        return index < 0 || index >= row.size() ? "" : row.get(index);
    }

    private record Signed(BigDecimal amount, boolean credit) {
    }

    /**
     * One statement line, either parsed or explained.
     *
     * @param duplicateAction {@code merge}, {@code review}, or null when unique
     */
    public record ParsedRow(
            int rowNumber,
            Instant occurredAt,
            LocalDate date,
            String description,
            BigDecimal amount,
            String direction,
            String reference,
            String error) {

        static ParsedRow failed(int rowNumber, String error) {
            return new ParsedRow(rowNumber, null, null, null, null, null, null, error);
        }

        public boolean isValid() {
            return error == null;
        }
    }
}
