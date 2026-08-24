package com.expensetracker.api.sms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The edges of {@link SmsFilter} that are awkward to express as example
 * messages: boundary lengths, the precedence between overlapping rules, and the
 * exact point at which a sender stops being a shortcode and becomes a person.
 */
class SmsFilterTest {

    private static final String ALERT =
            "Rs 450.00 debited from a/c **1234 on 04-02-26 to VPA swiggy@ybl.";

    @Nested
    @DisplayName("who sent it")
    class SenderRules {

        @Test
        void sixDigitShortcodeIsABusiness() {
            assertThat(SmsFilter.isPersonalNumber("561617")).isFalse();
        }

        @Test
        void sevenDigitsIsAlreadyTooLongForAShortcode() {
            assertThat(SmsFilter.isPersonalNumber("5616171")).isTrue();
        }

        @Test
        void anyLetterMeansASenderId() {
            // Bank alerts always carry letters, whether bare or with the
            // operator prefix the telco adds.
            assertThat(SmsFilter.isPersonalNumber("HDFCBK")).isFalse();
            assertThat(SmsFilter.isPersonalNumber("AD-HDFCBK")).isFalse();
            assertThat(SmsFilter.isPersonalNumber("JK-SBIINB-S")).isFalse();
        }

        @Test
        void punctuationAndCountryCodesDoNotDisguiseAMobileNumber() {
            assertThat(SmsFilter.isPersonalNumber("+91 98123-45678")).isTrue();
            assertThat(SmsFilter.isPersonalNumber("(+91) 9812345678")).isTrue();
            assertThat(SmsFilter.isPersonalNumber("098123 45678")).isTrue();
        }

        @Test
        void aPersonalNumberIsRejectedWithoutReadingTheMessage() {
            // The message below would sail through every content rule. It is
            // dropped purely on its sender, which is the behaviour that keeps
            // ordinary conversations off the server.
            SmsFilter.Decision decision = SmsFilter.check("+919812345678", ALERT);

            assertThat(decision.accepted()).isFalse();
            assertThat(decision.reason()).isEqualTo(SmsFilter.Reason.PERSONAL_SENDER);
        }
    }

    @Nested
    @DisplayName("size limits")
    class Bounds {

        @Test
        void aBodyAtTheLimitIsStillConsidered() {
            String padding = "x".repeat(SmsFilter.MAX_BODY_LENGTH - ALERT.length());

            assertThat(SmsFilter.check("AD-HDFCBK", ALERT + padding).accepted()).isTrue();
        }

        @Test
        void onePastTheLimitIsNotATextMessage() {
            String padding = "x".repeat(SmsFilter.MAX_BODY_LENGTH - ALERT.length() + 1);
            SmsFilter.Decision decision = SmsFilter.check("AD-HDFCBK", ALERT + padding);

            assertThat(decision.accepted()).isFalse();
            assertThat(decision.reason()).isEqualTo(SmsFilter.Reason.MALFORMED);
        }

        @Test
        void nullsAreTreatedAsMalformedRatherThanThrowing() {
            // These arrive from a JSON body, so absent fields are entirely
            // routine. A batch of forty messages must not fail because one of
            // them had no body.
            assertThat(SmsFilter.check("AD-HDFCBK", null).reason())
                    .isEqualTo(SmsFilter.Reason.MALFORMED);
            assertThat(SmsFilter.check(null, ALERT).reason())
                    .isEqualTo(SmsFilter.Reason.UNKNOWN_SENDER);
        }
    }

    @Nested
    @DisplayName("when several rules apply at once")
    class Precedence {

        @Test
        void anOtpWinsOverTheAlertItDescribes() {
            // This is the case that would silently double every online card
            // payment: the OTP names the amount and the merchant, so it parses
            // perfectly, and then the genuine alert arrives too.
            SmsFilter.Decision decision = SmsFilter.check(
                    "AD-HDFCBK",
                    "OTP 724193 for Rs 2,500.00 debited from HDFC Card XX9021 at AMAZON.");

            assertThat(decision.reason()).isEqualTo(SmsFilter.Reason.OTP_CODE);
        }

        @Test
        void aFailedPaymentIsNotAnExpenseEvenThoughItReadsLikeOne() {
            SmsFilter.Decision decision = SmsFilter.check(
                    "AD-PAYTMB",
                    "Rs 250.00 debited for VPA auto@paytm has failed and will be returned.");

            assertThat(decision.reason()).isEqualTo(SmsFilter.Reason.NOT_SETTLED);
        }

        @Test
        void aPersonalSenderOutranksEveryContentRule() {
            SmsFilter.Decision decision =
                    SmsFilter.check("+919812345678", "OTP 1234 for Rs 100 debited");

            assertThat(decision.reason()).isEqualTo(SmsFilter.Reason.PERSONAL_SENDER);
        }
    }

    @Nested
    @DisplayName("what counts as money")
    class Amounts {

        @Test
        void bareNumbersAreNotAmounts() {
            // Without this the filter would keep half the inbox: order numbers,
            // dates and "reply 2 to opt out" all look like figures.
            SmsFilter.Decision decision =
                    SmsFilter.check("AD-BLUDRT", "Your parcel 4471 was debited from the hub.");

            assertThat(decision.reason()).isEqualTo(SmsFilter.Reason.NO_AMOUNT);
        }

        @Test
        void theUsualIndianSpellingsAllCount() {
            for (String amount : new String[] {"Rs 500", "Rs.500", "INR 500", "₹500", "inr 500.50"}) {
                assertThat(SmsFilter.check("AD-HDFCBK", amount + " debited from a/c XX1").accepted())
                        .as(amount)
                        .isTrue();
            }
        }

        @Test
        void anAmountAloneIsNotEnough() {
            SmsFilter.Decision decision =
                    SmsFilter.check("AD-HDFCBK", "Your balance is Rs 41,220.55 as on 07-02-26.");

            assertThat(decision.reason()).isEqualTo(SmsFilter.Reason.NO_TRANSACTION_VERB);
        }

        @Test
        void theVerbMustBeInThePastTense() {
            // "spend" is an instruction and "spent" is a receipt. Leaning on
            // tense is what keeps offers out without maintaining a blocklist of
            // every campaign a bank has ever run.
            assertThat(SmsFilter.check("VM-SBICRD", "Spend Rs 2,000 and get Rs 200 back").accepted())
                    .isFalse();
            assertThat(SmsFilter.check("VM-SBICRD", "Rs 2,000 spent on your card").accepted())
                    .isTrue();
        }
    }
}
