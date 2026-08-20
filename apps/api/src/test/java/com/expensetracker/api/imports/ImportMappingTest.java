package com.expensetracker.api.imports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Column detection against the header rows real Indian banks export.
 *
 * <p>The user always gets to correct the guess, but a good guess is what makes
 * import usable — and a bad one that goes unnoticed files the closing balance
 * as the amount.
 */
class ImportMappingTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Nested
    @DisplayName("real bank headers")
    class RealBanks {

        @Test
        @DisplayName("HDFC: split withdrawal and deposit columns")
        void hdfc() {
            List<String> headers = List.of("Date", "Narration", "Chq./Ref.No.", "Value Dt",
                    "Withdrawal Amt.", "Deposit Amt.", "Closing Balance");

            ImportMapping mapping = ImportMapping.detect(headers,
                    List.of(List.of("03/04/25", "UPI-SWIGGY", "0000123", "03/04/25", "450.00", "",
                            "12,300.00")));

            assertThat(headers.get(mapping.dateColumn())).isEqualTo("Date");
            assertThat(headers.get(mapping.descriptionColumn())).isEqualTo("Narration");
            assertThat(headers.get(mapping.debitColumn())).isEqualTo("Withdrawal Amt.");
            assertThat(headers.get(mapping.creditColumn())).isEqualTo("Deposit Amt.");
            assertThat(headers.get(mapping.referenceColumn())).isEqualTo("Chq./Ref.No.");
            assertThat(mapping.amountColumn())
                    .as("a split statement must not also pick a single amount column")
                    .isEqualTo(-1);
            assertThat(mapping.isUsable()).isTrue();
        }

        @Test
        @DisplayName("ICICI: withdrawal and deposit alongside a balance")
        void icici() {
            List<String> headers = List.of("S No.", "Value Date", "Transaction Date",
                    "Cheque Number", "Transaction Remarks", "Withdrawal Amount (INR )",
                    "Deposit Amount (INR )", "Balance (INR )");

            ImportMapping mapping = ImportMapping.detect(headers,
                    List.of(List.of("1", "03/04/2025", "03/04/2025", "", "UPI/SWIGGY", "450.00",
                            "0.00", "12,300.00")));

            assertThat(headers.get(mapping.dateColumn())).isEqualTo("Transaction Date");
            assertThat(headers.get(mapping.descriptionColumn())).isEqualTo("Transaction Remarks");
            assertThat(headers.get(mapping.debitColumn())).isEqualTo("Withdrawal Amount (INR )");
            assertThat(headers.get(mapping.creditColumn())).isEqualTo("Deposit Amount (INR )");
        }

        @Test
        @DisplayName("SBI: debit and credit columns")
        void sbi() {
            List<String> headers = List.of("Txn Date", "Value Date", "Description", "Ref No./Cheque No.",
                    "Debit", "Credit", "Balance");

            ImportMapping mapping = ImportMapping.detect(headers,
                    List.of(List.of("3 Apr 2025", "3 Apr 2025", "UPI/SWIGGY", "123456", "450.00",
                            "", "12,300.00")));

            assertThat(headers.get(mapping.dateColumn())).isEqualTo("Txn Date");
            assertThat(headers.get(mapping.descriptionColumn())).isEqualTo("Description");
            assertThat(headers.get(mapping.debitColumn())).isEqualTo("Debit");
            assertThat(headers.get(mapping.creditColumn())).isEqualTo("Credit");
        }

        @Test
        @DisplayName("Axis: one unsigned amount plus a DR/CR column")
        void axis() {
            List<String> headers = List.of("Tran Date", "Particulars", "Amount", "DR|CR",
                    "Balance");

            ImportMapping mapping = ImportMapping.detect(headers,
                    List.of(List.of("03-04-2025", "UPI/SWIGGY", "450.00", "DR", "12300.00")));

            assertThat(headers.get(mapping.dateColumn())).isEqualTo("Tran Date");
            assertThat(headers.get(mapping.amountColumn())).isEqualTo("Amount");
            assertThat(headers.get(mapping.typeColumn())).isEqualTo("DR|CR");
        }

        @Test
        @DisplayName("credit card export: a single signed amount column")
        void singleAmountColumn() {
            List<String> headers = List.of("Transaction Date", "Merchant", "Amount");

            ImportMapping mapping = ImportMapping.detect(headers,
                    List.of(List.of("03/04/2025", "SWIGGY", "-450.00")));

            assertThat(headers.get(mapping.amountColumn())).isEqualTo("Amount");
            assertThat(mapping.debitColumn()).isEqualTo(-1);
            assertThat(mapping.creditColumn()).isEqualTo(-1);
            assertThat(mapping.isUsable()).isTrue();
        }
    }

    @Nested
    @DisplayName("refusing to guess")
    class Gaps {

        @Test
        @DisplayName("a balance column is never mistaken for the amount")
        void skipsBalance() {
            List<String> headers = List.of("Date", "Narration", "Closing Balance");

            ImportMapping mapping = ImportMapping.detect(headers,
                    List.of(List.of("03/04/2025", "UPI/SWIGGY", "12,300.00")));

            assertThat(mapping.amountColumn()).isEqualTo(-1);
            assertThat(mapping.isUsable()).isFalse();
            assertThat(mapping.describeGaps()).contains("amount");
        }

        @Test
        @DisplayName("says so when there is no date column")
        void noDate() {
            ImportMapping mapping = ImportMapping.detect(List.of("Narration", "Amount"),
                    List.of(List.of("UPI/SWIGGY", "450.00")));

            assertThat(mapping.isUsable()).isFalse();
            assertThat(mapping.describeGaps()).contains("date");
        }

        @Test
        @DisplayName("infers month-first from the data, not from a default")
        void infersDateOrder() {
            ImportMapping mapping = ImportMapping.detect(List.of("Date", "Amount"),
                    List.of(List.of("04/17/2025", "450.00")));

            assertThat(mapping.dayFirst()).isFalse();
        }

        @Test
        @DisplayName("a two-letter hint never matches a longer unrelated header")
        void shortHintsAreExactOnly() {
            List<String> headers = List.of("Date", "Address", "Amount");

            ImportMapping mapping = ImportMapping.detect(headers,
                    List.of(List.of("03/04/2025", "12 MG ROAD", "-450.00")));

            assertThat(mapping.debitColumn())
                    .as("'Address' contains 'dr' but is not a withdrawal column")
                    .isEqualTo(-1);
            assertThat(headers.get(mapping.amountColumn())).isEqualTo("Amount");
        }

        @Test
        @DisplayName("no two roles resolve to the same column")
        void rolesAreDistinct() {
            List<String> headers = List.of("Transaction Date", "Transaction Details",
                    "Transaction Amount", "Transaction Type");

            ImportMapping mapping = ImportMapping.detect(headers,
                    List.of(List.of("03/04/2025", "SWIGGY", "450.00", "DR")));

            assertThat(List.of(mapping.dateColumn(), mapping.descriptionColumn(),
                    mapping.amountColumn(), mapping.typeColumn()))
                    .doesNotHaveDuplicates();
        }
    }

    @Nested
    @DisplayName("reading rows through the detected mapping")
    class EndToEnd {

        @Test
        @DisplayName("a withdrawal becomes a debit of that amount")
        void hdfcWithdrawal() {
            List<List<String>> file = CsvParser.parse("""
                    Date,Narration,Chq./Ref.No.,Withdrawal Amt.,Deposit Amt.,Closing Balance
                    03/04/25,"UPI-SWIGGY, BANGALORE",0000123,450.00,,12300.00
                    """);

            ImportMapping mapping = ImportMapping.detect(file.get(0), file.subList(1, file.size()));
            StatementRowReader.ParsedRow row =
                    new StatementRowReader(mapping, IST).read(1, file.get(1));

            assertThat(row.isValid()).isTrue();
            assertThat(row.amount()).isEqualByComparingTo("450.00");
            assertThat(row.direction()).isEqualTo("debit");
            assertThat(row.date()).isEqualTo(LocalDate.of(2025, 4, 3));
            assertThat(row.description()).isEqualTo("UPI-SWIGGY, BANGALORE");
            assertThat(row.reference()).isEqualTo("0000123");
        }

        @Test
        @DisplayName("a deposit becomes a credit")
        void hdfcDeposit() {
            List<List<String>> file = CsvParser.parse("""
                    Date,Narration,Withdrawal Amt.,Deposit Amt.,Closing Balance
                    03/04/25,SALARY,0.00,"85,000.00",97300.00
                    """);

            ImportMapping mapping = ImportMapping.detect(file.get(0), file.subList(1, file.size()));
            StatementRowReader.ParsedRow row =
                    new StatementRowReader(mapping, IST).read(1, file.get(1));

            assertThat(row.direction()).isEqualTo("credit");
            assertThat(row.amount()).isEqualByComparingTo(new BigDecimal("85000.00"));
        }

        @Test
        @DisplayName("a DR indicator overrides the unsigned amount")
        void axisIndicator() {
            List<List<String>> file = CsvParser.parse("""
                    Tran Date,Particulars,Amount,DR|CR,Balance
                    03-04-2025,UPI/SWIGGY,450.00,DR,12300.00
                    03-04-2025,SALARY,85000.00,CR,97300.00
                    """);

            ImportMapping mapping = ImportMapping.detect(file.get(0), file.subList(1, file.size()));
            StatementRowReader reader = new StatementRowReader(mapping, IST);

            assertThat(reader.read(1, file.get(1)).direction())
                    .as("an unsigned withdrawal must not import as income")
                    .isEqualTo("debit");
            assertThat(reader.read(2, file.get(2)).direction()).isEqualTo("credit");
        }

        @Test
        @DisplayName("a statement date lands at midday, so no zone shift moves it")
        void anchorsAtMidday() {
            List<List<String>> file = CsvParser.parse("""
                    Date,Narration,Amount
                    03/04/2025,SWIGGY,-450.00
                    """);

            ImportMapping mapping = ImportMapping.detect(file.get(0), file.subList(1, file.size()));
            StatementRowReader.ParsedRow row =
                    new StatementRowReader(mapping, IST).read(1, file.get(1));

            assertThat(row.occurredAt())
                    .isEqualTo(LocalDate.of(2025, 4, 3).atTime(12, 0).atZone(IST).toInstant());
        }

        @Test
        @DisplayName("an unreadable row is reported, never silently dropped")
        void reportsBadRow() {
            List<List<String>> file = CsvParser.parse("""
                    Date,Narration,Amount
                    Opening Balance,,12300.00
                    """);

            ImportMapping mapping = ImportMapping.detect(file.get(0), file.subList(1, file.size()));
            StatementRowReader.ParsedRow row =
                    new StatementRowReader(mapping, IST).read(1, file.get(1));

            assertThat(row.isValid()).isFalse();
            assertThat(row.error()).contains("date");
        }

        @Test
        @DisplayName("a zero-amount line is reported rather than stored")
        void rejectsZero() {
            List<List<String>> file = CsvParser.parse("""
                    Date,Narration,Withdrawal Amt.,Deposit Amt.
                    03/04/2025,NO-OP,0.00,0.00
                    """);

            ImportMapping mapping = ImportMapping.detect(file.get(0), file.subList(1, file.size()));
            StatementRowReader.ParsedRow row =
                    new StatementRowReader(mapping, IST).read(1, file.get(1));

            assertThat(row.isValid()).isFalse();
        }

        @Test
        @DisplayName("a short row does not throw, it reports what is missing")
        void raggedRow() {
            ImportMapping mapping = new ImportMapping(0, 1, 2, -1, -1, -1, -1, true);
            StatementRowReader.ParsedRow row =
                    new StatementRowReader(mapping, IST).read(1, List.of("03/04/2025"));

            assertThat(row.isValid()).isFalse();
            assertThat(row.error()).contains("amount");
        }
    }
}
