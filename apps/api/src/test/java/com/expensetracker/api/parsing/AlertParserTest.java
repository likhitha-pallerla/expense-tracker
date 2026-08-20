package com.expensetracker.api.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rules that ship, against the alerts they will actually meet.
 *
 * <p>The samples are written the way banks write them — inconsistent spacing,
 * "Rs." and "INR" and "₹" in the same product, the merchant sometimes named and
 * sometimes a UPI handle. Anything tidier than this would prove nothing.
 */
class AlertParserTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Instant RECEIVED =
            LocalDate.of(2025, 8, 12).atTime(15, 30).atZone(IST).toInstant();

    private static final List<ParserRule> RULES = SeededRules.load();

    private static ParsedAlert parse(String subject, String body) {
        return AlertParser.parse("alerts@hdfcbank.net", subject, body, RECEIVED, IST, RULES);
    }

    @Test
    @DisplayName("the migration still contains rules this test can read")
    void rulesLoad() {
        assertThat(RULES).hasSizeGreaterThanOrEqualTo(6);
        assertThat(RULES).allSatisfy(rule -> assertThat(rule.isBuiltIn()).isTrue());
    }

    // ---- UPI ---------------------------------------------------------------

    @Test
    @DisplayName("a UPI payment names the merchant by its handle")
    void upiDebit() {
        ParsedAlert parsed = parse("Alert: UPI txn",
                "Dear Customer, Rs.450.00 has been debited from A/c XXXXXX1234 on 12-08-25 "
                        + "to VPA swiggy@icici. UPI Ref: 522412345678. -HDFC Bank");

        assertThat(parsed.isSuccess()).isTrue();
        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("450.00"));
        assertThat(parsed.direction()).isEqualTo("debit");
        assertThat(parsed.merchant()).isEqualTo("swiggy@icici");
        assertThat(parsed.last4()).isEqualTo("1234");
        assertThat(parsed.reference()).isEqualTo("522412345678");
        assertThat(parsed.dateExact()).isTrue();
    }

    @Test
    @DisplayName("money arriving by UPI is a credit, not an expense")
    void upiCredit() {
        ParsedAlert parsed = parse("UPI credit",
                "Your A/c XX5678 is credited with INR 2,500.00 on 12/08/2025 "
                        + "from VPA ramesh.k@okaxis (UPI Ref 987654321012).");

        assertThat(parsed.isSuccess()).isTrue();
        assertThat(parsed.isCredit()).isTrue();
        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("2500.00"));
        assertThat(parsed.merchant()).isEqualTo("ramesh.k@okaxis");
        assertThat(parsed.last4()).isEqualTo("5678");
    }

    @Test
    @DisplayName("lakh grouping survives the whole path")
    void lakhAmount() {
        ParsedAlert parsed = parse("UPI txn",
                "Rs 1,23,456.78 debited from A/c XX1111 on 12-08-25 to VPA builder@ybl. "
                        + "Ref: 400011112222");

        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("123456.78"));
    }

    @Test
    @DisplayName("the rupee sign is read as well as the words")
    void rupeeSign() {
        ParsedAlert parsed = parse("Payment",
                "₹899.00 debited from A/c XX2222 on 12-08-25 to VPA netflix@axl. Ref: 771122334455");

        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("899.00"));
    }

    // ---- Cards -------------------------------------------------------------

    @Test
    @DisplayName("a card purchase names the merchant in words")
    void cardSpend() {
        ParsedAlert parsed = parse("Transaction alert",
                "INR 1,250.75 spent on HDFC Bank Card ending 4321 at AMAZON INDIA on 11-08-2025. "
                        + "Auth code: 553311.");

        assertThat(parsed.isSuccess()).isTrue();
        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("1250.75"));
        assertThat(parsed.direction()).isEqualTo("debit");
        assertThat(parsed.merchant()).isEqualTo("AMAZON INDIA");
        assertThat(parsed.last4()).isEqualTo("4321");
    }

    @Test
    @DisplayName("a merchant name does not run into the sentence after it")
    void merchantStopsAtOn() {
        ParsedAlert parsed = parse("Card txn",
                "Rs 300.00 spent on your card ending 9876 at BLUE TOKAI COFFEE on 12-08-25.");

        assertThat(parsed.merchant()).isEqualTo("BLUE TOKAI COFFEE");
    }

    // ---- Cash --------------------------------------------------------------

    @Test
    @DisplayName("an ATM withdrawal is an expense with no merchant")
    void atm() {
        ParsedAlert parsed = parse("ATM withdrawal",
                "Rs.5000.00 withdrawn from A/c XX3456 at ATM on 12-08-25. Ref 998877665544.");

        assertThat(parsed.isSuccess()).isTrue();
        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("5000.00"));
        assertThat(parsed.direction()).isEqualTo("debit");
        assertThat(parsed.last4()).isEqualTo("3456");
    }

    // ---- The catch-alls ----------------------------------------------------

    @Test
    @DisplayName("a bank we have never seen still produces a transaction")
    void unknownBank() {
        ParsedAlert parsed = parse("Debit alert",
                "Your account XX7777 has been debited by Rs. 799.00 towards ELECTRICITY BOARD "
                        + "on 12-08-25.");

        assertThat(parsed.isSuccess()).isTrue();
        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("799.00"));
        assertThat(parsed.direction()).isEqualTo("debit");
        assertThat(parsed.last4()).isEqualTo("7777");
    }

    @Test
    @DisplayName("a salary credit is income")
    void salary() {
        ParsedAlert parsed = parse("Salary credited",
                "A/c XX4444 credited with INR 85,000.00 on 01-08-2025 by NEFT from ACME PVT LTD. "
                        + "Ref UTR: N123456789012.");

        assertThat(parsed.isSuccess()).isTrue();
        assertThat(parsed.isCredit()).isTrue();
        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("85000.00"));
    }

    // ---- What it must not do ----------------------------------------------

    @Test
    @DisplayName("a message with no amount is reported, not invented")
    void noAmount() {
        ParsedAlert parsed = parse("Account update",
                "Your account XX1234 has been debited. Please check your statement.");

        assertThat(parsed.isSuccess()).isFalse();
        assertThat(parsed.problem()).contains("amount");
        assertThat(parsed.amount()).isNull();
    }

    @Test
    @DisplayName("mail that is not about a payment matches nothing")
    void notAPayment() {
        ParsedAlert parsed = parse("Your monthly newsletter",
                "Here are this month's articles about saving money. Read more on our blog.");

        assertThat(parsed.isSuccess()).isFalse();
        assertThat(parsed.ruleId()).isNull();
        assertThat(parsed.problem()).contains("No rule recognised");
    }

    @Test
    @DisplayName("an OTP is not a payment even though it carries an amount")
    void otp() {
        // The word "debited" is absent, which is what keeps this out.
        ParsedAlert parsed = parse("OTP for your transaction",
                "123456 is your OTP for a transaction of Rs 2,000.00. Valid for 10 minutes.");

        assertThat(parsed.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("a placeholder reference is discarded rather than used to merge")
    void placeholderReference() {
        ParsedAlert parsed = parse("UPI txn",
                "Rs.100.00 debited from A/c XX1234 on 12-08-25 to VPA shop@upi. Ref: XXXXXXXX.");

        assertThat(parsed.isSuccess()).isTrue();
        assertThat(parsed.reference()).isNull();
    }

    @Test
    @DisplayName("an unreadable date falls back and says so")
    void unreadableDate() {
        ParsedAlert parsed = parse("UPI txn",
                "Rs.250.00 debited from A/c XX1234 to VPA store@upi. Ref: 123456789012");

        assertThat(parsed.isSuccess()).isTrue();
        assertThat(parsed.occurredAt()).isEqualTo(RECEIVED);
        assertThat(parsed.dateExact()).isFalse();
    }

    @Test
    @DisplayName("the whole alert in the subject line still works")
    void subjectOnly() {
        ParsedAlert parsed = parse(
                "Rs.320.00 debited from A/c XX1234 on 12-08-25 to VPA autodriver@paytm", "");

        assertThat(parsed.isSuccess()).isTrue();
        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("320.00"));
    }

    @Test
    @DisplayName("a user's own rule beats every built-in one")
    void userRuleWins() {
        ParserRule mine = new ParserRule(
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                "My credit union",
                null,
                java.util.regex.Pattern.compile("(?i)paid out"),
                java.util.Map.of(
                        ParserRule.AMOUNT, new Extractor(
                                java.util.regex.Pattern.compile("(?i)amt\\s+([0-9.]+)"), 1,
                                Extractor.Kind.AMOUNT),
                        ParserRule.DIRECTION, new Extractor(
                                java.util.regex.Pattern.compile("(?i)(paid)"), 1,
                                Extractor.Kind.DIRECTION)),
                500);

        List<ParserRule> ordered = new java.util.ArrayList<>();
        ordered.add(mine);
        ordered.addAll(RULES);

        ParsedAlert parsed = AlertParser.parse(null, "Paid out",
                "Paid out amt 75.00, debited today.", RECEIVED, IST, ordered);

        assertThat(parsed.ruleName()).isEqualTo("My credit union");
        assertThat(parsed.amount()).isEqualByComparingTo(new BigDecimal("75.00"));
    }
}
