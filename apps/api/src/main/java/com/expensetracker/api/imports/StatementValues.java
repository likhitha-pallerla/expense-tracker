package com.expensetracker.api.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Parses the amount and date strings that appear in real bank statements.
 *
 * <p>These are far less regular than they look — Indian digit grouping,
 * {@code Dr}/{@code Cr} suffixes, accounting parentheses for negatives, and at
 * least six date layouts. A misread here writes a wrong number into the
 * ledger, so every case is handled explicitly rather than by a lenient regex.
 */
public final class StatementValues {

    private static final List<DateTimeFormatter> NAMED_MONTH = List.of(
            DateTimeFormatter.ofPattern("d-MMM-yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-yy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d MMM yy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d/MMM/yyyy", Locale.ENGLISH));

    private StatementValues() {
    }

    // ---- amounts -----------------------------------------------------------

    /**
     * Reads an amount, returning empty for the blanks and dashes banks use to
     * mean "nothing in this column".
     *
     * <p>The result is signed: {@code (500)}, {@code -500} and {@code 500 Dr}
     * all read as negative. A caller reading a dedicated debit column takes the
     * magnitude; a caller reading a single signed column keeps the sign.
     */
    public static Optional<BigDecimal> parseAmount(String raw) {
        if (raw == null) {
            return Optional.empty();
        }

        String value = raw.trim();
        if (value.isEmpty() || value.equals("-") || value.equals("–") || value.equals("—")) {
            return Optional.empty();
        }

        boolean negative = false;

        // Accounting style: (1,234.56) means an outflow.
        if (value.startsWith("(") && value.endsWith(")")) {
            negative = true;
            value = value.substring(1, value.length() - 1);
        }

        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.endsWith("DR") || upper.endsWith("DEBIT")) {
            negative = true;
            value = value.substring(0, value.length() - (upper.endsWith("DR") ? 2 : 5));
        } else if (upper.endsWith("CR") || upper.endsWith("CREDIT")) {
            value = value.substring(0, value.length() - (upper.endsWith("CR") ? 2 : 6));
        }

        // Strip currency symbols, grouping separators and stray spaces. Indian
        // grouping (1,23,456.78) needs no special handling once commas go.
        value = value.replaceAll("[^0-9.\\-+]", "");

        if (value.startsWith("-")) {
            negative = true;
            value = value.substring(1);
        } else if (value.startsWith("+")) {
            value = value.substring(1);
        }

        if (value.isEmpty() || value.equals(".")) {
            return Optional.empty();
        }

        try {
            BigDecimal amount = new BigDecimal(value);
            return Optional.of(negative ? amount.negate() : amount);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    // ---- dates -------------------------------------------------------------

    /**
     * Works out whether a column writes the day or the month first.
     *
     * <p>{@code 03/04/2025} is genuinely ambiguous, but a whole column rarely
     * is: one value with a leading number above 12 settles it. Guessing wrong
     * would silently file transactions in the wrong month, so the data decides
     * rather than a default.
     */
    public static boolean isDayFirst(Collection<String> samples) {
        for (String sample : samples) {
            int[] parts = numericParts(sample);
            if (parts == null) {
                continue;
            }
            if (parts[0] > 12) {
                return true;
            }
            if (parts[1] > 12) {
                return false;
            }
        }
        // India-first product, and dd/MM is the overwhelming local convention.
        return true;
    }

    public static Optional<LocalDate> parseDate(String raw, boolean dayFirst) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }

        // Statements often carry a time alongside the date; it is noise here.
        String value = raw.trim().split("[ T](?=\\d{1,2}:)")[0].trim();

        try {
            return Optional.of(LocalDate.parse(value));
        } catch (DateTimeParseException ignored) {
            // Not ISO; fall through.
        }

        if (value.matches("\\d{4}[/.]\\d{1,2}[/.]\\d{1,2}")) {
            String[] parts = value.split("[/.]");
            return build(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]));
        }

        // A month name removes all ambiguity, so try those before numbers.
        String normalised = value.replace('.', '-').replaceAll("\\s+", " ");
        for (DateTimeFormatter formatter : NAMED_MONTH) {
            try {
                return Optional.of(LocalDate.parse(normalised, formatter));
            } catch (DateTimeParseException ignored) {
                // Try the next layout.
            }
        }

        int[] parts = numericParts(value);
        if (parts == null) {
            return Optional.empty();
        }

        int day = dayFirst ? parts[0] : parts[1];
        int month = dayFirst ? parts[1] : parts[0];
        return build(expandYear(parts[2]), month, day);
    }

    private static Optional<LocalDate> build(int year, int month, int day) {
        try {
            return Optional.of(LocalDate.of(year, month, day));
        } catch (java.time.DateTimeException ex) {
            return Optional.empty();
        }
    }

    /** Splits {@code d/m/y}, {@code d-m-y} or {@code d.m.y} into three numbers. */
    private static int[] numericParts(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().split("[ T](?=\\d{1,2}:)")[0].trim();
        if (!value.matches("\\d{1,2}[/\\-.]\\d{1,2}[/\\-.]\\d{2,4}")) {
            return null;
        }
        String[] parts = value.split("[/\\-.]");
        return new int[] {
                Integer.parseInt(parts[0]),
                Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]),
        };
    }

    /** Two-digit years in a bank statement are this century. */
    private static int expandYear(int year) {
        return year < 100 ? 2000 + year : year;
    }
}
