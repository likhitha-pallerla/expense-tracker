package com.expensetracker.api.imports;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which CSV column holds what.
 *
 * <p>Every bank names its columns differently, so this is detected from the
 * header row and then shown to the user for correction — a wrong guess would
 * import the closing balance as the amount.
 *
 * <p>Indices are zero-based; {@code -1} means "not present".
 *
 * @param dateColumn        when the payment happened
 * @param descriptionColumn the narration, used as both description and merchant
 * @param amountColumn      a single signed column, when the bank uses one
 * @param debitColumn       money out, when the bank splits the two
 * @param creditColumn      money in, when the bank splits the two
 * @param referenceColumn   the bank reference (RRN/UTR/cheque number)
 * @param typeColumn        a separate DR/CR indicator, when the bank uses one
 * @param dayFirst          whether {@code 03/04} means 3 April or 4 March
 */
public record ImportMapping(
        int dateColumn,
        int descriptionColumn,
        int amountColumn,
        int debitColumn,
        int creditColumn,
        int referenceColumn,
        int typeColumn,
        boolean dayFirst) {

    private static final List<String> DATE_HINTS = List.of(
            "transactiondate", "txndate", "date", "valuedate", "postingdate", "bookingdate");

    private static final List<String> DESCRIPTION_HINTS = List.of(
            "narration", "particulars", "description", "transactiondetails", "details",
            "remarks", "merchant", "payee", "transactionremarks");

    private static final List<String> DEBIT_HINTS = List.of(
            "withdrawalamt", "withdrawal", "debitamount", "debit", "paymentamount", "dr");

    private static final List<String> CREDIT_HINTS = List.of(
            "depositamt", "deposit", "creditamount", "credit", "receiptamount", "cr");

    /**
     * Hints too short to be matched as substrings.
     *
     * <p>{@code dr} appears inside {@code Address} and {@code DR|CR}; matching
     * it loosely would file an address column as the withdrawal amount.
     */
    private static final Set<String> EXACT_ONLY = Set.of("dr", "cr", "c", "d");

    private static final List<String> AMOUNT_HINTS = List.of(
            "transactionamount", "amount", "amt", "value");

    private static final List<String> REFERENCE_HINTS = List.of(
            "chqrefno", "chequeno", "referenceno", "reference", "refno", "utr", "rrn",
            "transactionid", "txnid", "chequereferenceno");

    // Several Indian banks put the amount in one unsigned column and the
    // direction in a separate DR/CR column. Missing it would import every
    // withdrawal as income.
    private static final List<String> TYPE_HINTS = List.of(
            "drcr", "crdr", "debitcredit", "transactiontype", "txntype", "type", "indicator");

    /**
     * Guesses the layout from the header row and a sample of values.
     *
     * <p>Debit and credit are matched before a generic amount column, because a
     * statement with both would otherwise match "Withdrawal Amt." as the
     * amount and lose the direction.
     */
    public static ImportMapping detect(List<String> headers, List<List<String>> rows) {
        List<String> keys = headers.stream().map(ImportMapping::normalise).toList();

        int date = match(keys, DATE_HINTS);
        int description = match(keys, DESCRIPTION_HINTS, date);

        // Claimed first: a combined "DR|CR" header would otherwise be taken as
        // the withdrawal column and swallow the direction.
        int type = match(keys, TYPE_HINTS, date, description);

        int debit = match(keys, DEBIT_HINTS, date, description, type);
        int credit = match(keys, CREDIT_HINTS, date, description, type, debit);
        int reference = match(keys, REFERENCE_HINTS, date, description, type, debit, credit);

        // Only look for a single amount column when the bank has not split the
        // two, otherwise both readings would compete for the same row.
        int amount = (debit >= 0 && credit >= 0)
                ? -1
                : match(keys, AMOUNT_HINTS, date, description, type, debit, credit, reference);

        boolean dayFirst = date >= 0
                ? StatementValues.isDayFirst(column(rows, date))
                : true;

        return new ImportMapping(date, description, amount, debit, credit, reference,
                amount >= 0 ? type : -1, dayFirst);
    }

    /** True when there is enough here to read a date and an amount. */
    public boolean isUsable() {
        return dateColumn >= 0 && (amountColumn >= 0 || debitColumn >= 0 || creditColumn >= 0);
    }

    public String describeGaps() {
        if (dateColumn < 0) {
            return "No date column found. Pick the column holding the transaction date.";
        }
        return "No amount column found. Pick either a single amount column, or the "
                + "withdrawal and deposit columns.";
    }

    private static List<String> column(List<List<String>> rows, int index) {
        return rows.stream()
                .filter(row -> index < row.size())
                .map(row -> row.get(index))
                .filter(value -> !value.isBlank())
                .limit(200)
                .toList();
    }

    /**
     * Picks the header best matching a hint, preferring earlier (more specific)
     * hints and exact matches over partial ones.
     *
     * <p>Indices already claimed by another role are skipped, so two roles can
     * never resolve to the same column.
     */
    private static int match(List<String> keys, List<String> hints, int... taken) {
        for (String hint : hints) {
            for (int i = 0; i < keys.size(); i++) {
                if (isTaken(i, taken)) {
                    continue;
                }
                if (keys.get(i).equals(hint)) {
                    return i;
                }
            }
        }
        for (String hint : hints) {
            if (EXACT_ONLY.contains(hint)) {
                continue;
            }
            for (int i = 0; i < keys.size(); i++) {
                if (isTaken(i, taken)) {
                    continue;
                }
                String key = keys.get(i);
                // A running balance is never the transaction amount, and its
                // header often contains one of the same words.
                if (key.contains("balance")) {
                    continue;
                }
                if (key.contains(hint)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static boolean isTaken(int index, int[] taken) {
        for (int claimed : taken) {
            if (claimed == index) {
                return true;
            }
        }
        return false;
    }

    private static String normalise(String header) {
        return header == null
                ? ""
                : header.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
