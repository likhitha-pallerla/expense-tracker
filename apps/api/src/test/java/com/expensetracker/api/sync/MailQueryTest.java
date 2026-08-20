package com.expensetracker.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Deciding which mail is a payment alert")
class MailQueryTest {

    private static MailMessage message(String subject, String body) {
        return new MailMessage("id", "alerts@bank.example", subject, null, body, Instant.now());
    }

    @Nested
    @DisplayName("Real alerts get through")
    class Accepts {

        @Test
        @DisplayName("a UPI debit")
        void upiDebit() {
            assertThat(MailQuery.looksRelevant(message(
                    "Transaction Alert",
                    "Rs.450.00 has been debited from your A/c XX1234 on 12-05-24 to SWIGGY via UPI.")))
                    .isTrue();
        }

        @Test
        @DisplayName("a card purchase with the rupee symbol")
        void cardPurchase() {
            assertThat(MailQuery.looksRelevant(message(
                    "Your card was used",
                    "₹1,299 spent on your HDFC Credit Card XX9012 at AMAZON.")))
                    .isTrue();
        }

        @Test
        @DisplayName("a foreign currency charge")
        void foreignCurrency() {
            assertThat(MailQuery.looksRelevant(message(
                    "Payment received",
                    "You were charged USD 12.99 for your subscription.")))
                    .isTrue();
        }

        @Test
        @DisplayName("an amount written before the currency")
        void trailingCurrency() {
            assertThat(MailQuery.looksRelevant(message(
                    "Alert", "An amount of 500.00 INR was debited today.")))
                    .isTrue();
        }

        @Test
        @DisplayName("a credit card statement")
        void statement() {
            assertThat(MailQuery.looksRelevant(message(
                    "Your monthly statement",
                    "Total due Rs 12,345.67. Minimum due Rs 617.00. Due date 05-06-24.")))
                    .isTrue();
        }

        @Test
        @DisplayName("the amount only in the subject")
        void amountInSubject() {
            assertThat(MailQuery.looksRelevant(message(
                    "Rs 250 debited from your account",
                    "View this transaction in the app.")))
                    .isTrue();
        }

        @Test
        @DisplayName("a refund, which is money moving too")
        void refund() {
            assertThat(MailQuery.looksRelevant(message(
                    "Refund processed",
                    "A refund of Rs 799 has been credited to your account.")))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("Everything else is left alone")
    class Rejects {

        @Test
        @DisplayName("an ordinary email with no money in it")
        void noAmount() {
            assertThat(MailQuery.looksRelevant(message(
                    "Lunch tomorrow?", "Shall we say one o'clock at the usual place?")))
                    .isFalse();
        }

        @Test
        @DisplayName("a shopping advertisement, which has amounts but no transaction")
        void advertisement() {
            assertThat(MailQuery.looksRelevant(message(
                    "Sale now on",
                    "Shoes from Rs 999. Shirts from Rs 599. Shop the collection today.")))
                    .isFalse();
        }

        @Test
        @DisplayName("a loan offer from a bank, which has both amounts and payment words")
        void bankMarketing() {
            assertThat(MailQuery.looksRelevant(message(
                    "You are pre-approved!",
                    "Congratulations! You are eligible for a personal loan of Rs 5,00,000 "
                            + "with easy EMI payment options.")))
                    .isFalse();
        }

        @Test
        @DisplayName("a promotional mail that mentions a statement")
        void promotionalStatement() {
            assertThat(MailQuery.looksRelevant(message(
                    "Your statement is ready",
                    "Limited period offer: get Rs 500 cashback on your next transaction.")))
                    .isFalse();
        }

        @Test
        @DisplayName("a message with no body at all")
        void empty() {
            assertThat(MailQuery.looksRelevant(message("Rs 500 debited", null))).isFalse();
            assertThat(MailQuery.looksRelevant(message("Rs 500 debited", "   "))).isFalse();
        }

        @Test
        @DisplayName("nothing at all")
        void nullMessage() {
            assertThat(MailQuery.looksRelevant(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("Whole words only")
    class WordBoundaries {

        @Test
        @DisplayName("'billion' in an advert is not a 'bill'")
        void billionIsNotBill() {
            assertThat(MailQuery.looksRelevant(message(
                    "Market news",
                    "The fund crossed Rs 1,000 crore, up from a billion last year.")))
                    .isFalse();
        }

        @Test
        @DisplayName("'automatic' is not 'atm'")
        void automaticIsNotAtm() {
            assertThat(MailQuery.looksRelevant(message(
                    "Your automatic backup",
                    "Storage costs Rs 100 for the automatic plan you are viewing.")))
                    .isFalse();
        }

        @Test
        @DisplayName("but a real ATM withdrawal is")
        void realAtm() {
            assertThat(MailQuery.looksRelevant(message(
                    "ATM withdrawal",
                    "Rs 2,000 withdrawn at ATM on 12-05-24.")))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("The Gmail query")
    class GmailQuery {

        @Test
        @DisplayName("asks only for mail newer than the backfill window")
        void bounded() {
            String query = MailQuery.gmailQuery(Instant.now().minusSeconds(30L * 86400));
            assertThat(query).contains("newer_than:31d");
        }

        @Test
        @DisplayName("never asks for less than a day, however recent the cursor")
        void neverZeroDays() {
            String query = MailQuery.gmailQuery(Instant.now());
            assertThat(query).contains("newer_than:1d");
        }

        @Test
        @DisplayName("leaves spam and trash alone")
        void excludesSpam() {
            String query = MailQuery.gmailQuery(Instant.now().minusSeconds(86400));
            assertThat(query).contains("-in:spam").contains("-in:trash");
        }

        @Test
        @DisplayName("is broader than the local filter, so nothing is missed at the source")
        void broaderThanLocalFilter() {
            String query = MailQuery.gmailQuery(Instant.now().minusSeconds(86400));
            // No amount requirement in the remote query: an alert whose amount
            // is rendered as an image would still arrive and be judged here.
            assertThat(query).doesNotContain("₹").doesNotContain("Rs.");
        }
    }
}
