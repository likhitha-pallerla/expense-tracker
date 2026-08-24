package com.expensetracker.api.sms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint decides which uploads collapse into one row, so it is the
 * difference between a payment shown twice and a payment lost entirely.
 */
class SmsFingerprintTest {

    private static final Instant AT = Instant.parse("2026-02-04T09:14:02Z");
    private static final String BODY = "Rs 20 debited to VPA chaiwala@ybl";

    @Test
    void theSameMessageUploadedTwiceHashesTheSame() {
        // This is what makes a retry after a dropped connection harmless.
        assertThat(SmsFingerprint.of("AD-HDFCBK", AT, BODY))
                .isEqualTo(SmsFingerprint.of("AD-HDFCBK", AT, BODY));
    }

    @Test
    void identicalAlertsAtDifferentTimesStayApart() {
        // Two cups of tea. Without the timestamp the second purchase would be
        // taken for a duplicate of the first and silently dropped.
        String first = SmsFingerprint.of("AD-HDFCBK", AT, BODY);
        String second = SmsFingerprint.of("AD-HDFCBK", AT.plusSeconds(3600), BODY);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void subSecondPrecisionIsIgnored() {
        // Milliseconds survive one round trip and not another depending on how
        // a JSON library renders them. Nothing meaningful is lost by dropping
        // them, and a great deal of spurious duplication is avoided.
        String exact = SmsFingerprint.of("AD-HDFCBK", AT, BODY);
        String jittered = SmsFingerprint.of("AD-HDFCBK", AT.plusMillis(437), BODY);

        assertThat(exact).isEqualTo(jittered);
        assertThat(AT.truncatedTo(ChronoUnit.SECONDS)).isEqualTo(AT);
    }

    @Test
    void aDifferentSenderIsADifferentMessage() {
        // Two banks can word an alert identically. They are still two alerts.
        assertThat(SmsFingerprint.of("AD-HDFCBK", AT, BODY))
                .isNotEqualTo(SmsFingerprint.of("VM-ICICIB", AT, BODY));
    }

    @Test
    void wordingChangesTheFingerprint() {
        assertThat(SmsFingerprint.of("AD-HDFCBK", AT, "Rs 20 debited"))
                .isNotEqualTo(SmsFingerprint.of("AD-HDFCBK", AT, "Rs 21 debited"));
    }

    @Test
    void caseAndSpacingDoNot() {
        // Inherited from BodyHash: how a transport wrapped the text is not part
        // of the message.
        assertThat(SmsFingerprint.of("AD-HDFCBK", AT, "Rs 20  DEBITED  to VPA chaiwala@ybl"))
                .isEqualTo(SmsFingerprint.of("AD-HDFCBK", AT, "rs 20 debited to vpa chaiwala@ybl"));
    }

    @Test
    void aMissingTimestampFallsBackToTheMailBehaviour() {
        // Degrades to sender+body rather than inventing a time, which would
        // differ on every upload and duplicate the message without limit.
        assertThat(SmsFingerprint.of("AD-HDFCBK", null, BODY))
                .isEqualTo(SmsFingerprint.of("AD-HDFCBK", null, BODY));
        assertThat(SmsFingerprint.of("AD-HDFCBK", null, BODY))
                .isNotEqualTo(SmsFingerprint.of("AD-HDFCBK", AT, BODY));
    }
}
