package com.expensetracker.api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Bank CSVs are not well-formed CSV. Narrations contain commas, quotes and
 * newlines; Excel adds a BOM; Windows exports use CRLF. Any of these shifts
 * every column by one, which would import wrong amounts against wrong dates.
 */
class CsvParserTest {

    @Test
    @DisplayName("splits a plain file into rows and columns")
    void plainFile() {
        List<List<String>> rows = CsvParser.parse("Date,Amount\n01/04/2025,500\n");

        assertThat(rows).containsExactly(
                List.of("Date", "Amount"),
                List.of("01/04/2025", "500"));
    }

    @Test
    @DisplayName("keeps commas that are inside a quoted narration")
    void quotedComma() {
        List<List<String>> rows =
                CsvParser.parse("Date,Narration,Amount\n01/04/2025,\"SWIGGY, BANGALORE\",500\n");

        assertThat(rows.get(1)).containsExactly("01/04/2025", "SWIGGY, BANGALORE", "500");
    }

    @Test
    @DisplayName("reads a doubled quote as one literal quote")
    void escapedQuote() {
        List<List<String>> rows = CsvParser.parse("Narration\n\"PAID TO \"\"BLUE CAFE\"\"\"\n");

        assertThat(rows.get(1)).containsExactly("PAID TO \"BLUE CAFE\"");
    }

    @Test
    @DisplayName("keeps a quoted field that spans two lines as one field")
    void embeddedNewline() {
        List<List<String>> rows = CsvParser.parse("Narration,Amount\n\"UPI\nSWIGGY\",500\n");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("UPI\nSWIGGY", "500");
    }

    @Test
    @DisplayName("handles Windows line endings")
    void crlf() {
        List<List<String>> rows = CsvParser.parse("Date,Amount\r\n01/04/2025,500\r\n");

        assertThat(rows).containsExactly(
                List.of("Date", "Amount"),
                List.of("01/04/2025", "500"));
    }

    @Test
    @DisplayName("handles old Mac line endings")
    void loneCarriageReturn() {
        List<List<String>> rows = CsvParser.parse("Date,Amount\r01/04/2025,500");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("01/04/2025", "500");
    }

    @Test
    @DisplayName("strips the byte order mark Excel writes")
    void byteOrderMark() {
        List<List<String>> rows = CsvParser.parse("\uFEFFDate,Amount\n01/04/2025,500\n");

        assertThat(rows.get(0).get(0)).isEqualTo("Date");
    }

    @Test
    @DisplayName("drops blank lines rather than importing empty transactions")
    void blankLines() {
        List<List<String>> rows = CsvParser.parse("Date,Amount\n\n01/04/2025,500\n,\n\n");

        assertThat(rows).hasSize(2);
    }

    @Test
    @DisplayName("keeps the last row when the file has no trailing newline")
    void noTrailingNewline() {
        List<List<String>> rows = CsvParser.parse("Date,Amount\n01/04/2025,500");

        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("01/04/2025", "500");
    }

    @Test
    @DisplayName("keeps empty trailing cells so column positions stay aligned")
    void emptyTrailingCell() {
        List<List<String>> rows = CsvParser.parse("Date,Debit,Credit\n01/04/2025,500,\n");

        assertThat(rows.get(1)).containsExactly("01/04/2025", "500", "");
    }

    @Test
    @DisplayName("trims the padding banks leave around values")
    void trimsPadding() {
        List<List<String>> rows = CsvParser.parse("Date , Amount \n 01/04/2025 , 500 \n");

        assertThat(rows.get(0)).containsExactly("Date", "Amount");
        assertThat(rows.get(1)).containsExactly("01/04/2025", "500");
    }

    @Test
    @DisplayName("returns nothing for an empty file")
    void emptyInput() {
        assertThat(CsvParser.parse("")).isEmpty();
        assertThat(CsvParser.parse(null)).isEmpty();
    }

    @Nested
    @DisplayName("size limit")
    class SizeLimit {

        @Test
        @DisplayName("refuses a file large enough to exhaust the server")
        void refusesHugeFile() {
            StringBuilder csv = new StringBuilder("Date,Amount\n");
            for (int i = 0; i < CsvParser.MAX_ROWS + 10; i++) {
                csv.append("01/04/2025,500\n");
            }

            assertThatIllegalArgument(csv.toString());
        }

        private void assertThatIllegalArgument(String csv) {
            org.assertj.core.api.Assertions
                    .assertThatThrownBy(() -> CsvParser.parse(csv))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rows");
        }
    }
}
