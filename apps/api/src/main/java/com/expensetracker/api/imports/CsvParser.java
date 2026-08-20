package com.expensetracker.api.imports;

import java.util.ArrayList;
import java.util.List;

/**
 * A CSV reader for the messy files banks actually produce.
 *
 * <p>Splitting on commas is not enough: narrations routinely contain commas and
 * are therefore quoted, quotes are escaped by doubling, and a quoted field may
 * span several lines. Getting this wrong silently shifts every column, which
 * would import wrong amounts against wrong dates.
 */
public final class CsvParser {

    /** Refuse anything larger, rather than exhausting the server's heap. */
    public static final int MAX_ROWS = 20_000;

    private CsvParser() {
    }

    public static List<List<String>> parse(String input) {
        List<List<String>> rows = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return rows;
        }

        // Excel writes a UTF-8 BOM, which would otherwise become part of the
        // first header and stop it from ever matching.
        String text = input.startsWith("\uFEFF") ? input.substring(1) : input;

        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
                continue;
            }

            switch (c) {
                case '"' -> quoted = true;
                case ',' -> {
                    row.add(field.toString().trim());
                    field.setLength(0);
                }
                case '\r' -> {
                    // Swallow CR when CRLF; a lone CR still ends the row.
                    if (i + 1 >= text.length() || text.charAt(i + 1) != '\n') {
                        row.add(field.toString().trim());
                        field.setLength(0);
                        addRow(rows, row);
                        row = new ArrayList<>();
                    }
                }
                case '\n' -> {
                    row.add(field.toString().trim());
                    field.setLength(0);
                    addRow(rows, row);
                    row = new ArrayList<>();
                }
                default -> field.append(c);
            }

            if (rows.size() > MAX_ROWS) {
                throw new IllegalArgumentException(
                        "That file has more than " + MAX_ROWS + " rows. Split it and import in parts.");
            }
        }

        // A file not ending in a newline still has a final row to flush.
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString().trim());
            addRow(rows, row);
        }

        return rows;
    }

    /** Blank lines carry no data and would otherwise become empty transactions. */
    private static void addRow(List<List<String>> rows, List<String> row) {
        if (row.stream().anyMatch(value -> !value.isBlank())) {
            rows.add(row);
        }
    }
}
