package com.expensetracker.api.sms;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs {@link SmsFilter} against the corpus shared with the mobile app.
 *
 * <p>The examples live in {@code packages/shared/sms-filter-vectors.json} rather
 * than in this file so that the TypeScript filter on the handset is measured
 * against exactly the same messages. Two filters that are supposed to agree but
 * are tested separately will not stay in agreement for long, and the failure
 * mode is quiet: the phone starts uploading something the server then throws
 * away, or worse, stops uploading something the server would have accepted, and
 * transactions simply go missing with no error anywhere.
 */
class SmsFilterVectorTest {

    private static final Path VECTORS =
            Path.of("..", "..", "packages", "shared", "sms-filter-vectors.json");

    private static JsonNode load() throws IOException {
        assertThat(VECTORS)
                .as("shared vector file must exist; both the API and the mobile app read it")
                .exists();
        return new ObjectMapper().readTree(Files.readString(VECTORS));
    }

    @TestFactory
    List<DynamicTest> acceptsRealBankAlerts() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode vector : load().get("accept")) {
            String name = vector.get("name").asText();
            String sender = vector.get("sender").asText();
            String body = vector.get("body").asText();
            tests.add(DynamicTest.dynamicTest("accepts: " + name, () -> {
                SmsFilter.Decision decision = SmsFilter.check(sender, body);
                assertThat(decision.accepted())
                        .as("%s -- rejected as %s", name, decision.reason())
                        .isTrue();
            }));
        }
        assertThat(tests).as("corpus should not be empty").isNotEmpty();
        return tests;
    }

    @TestFactory
    List<DynamicTest> rejectsEverythingElse() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode vector : load().get("reject")) {
            String name = vector.get("name").asText();
            String sender = vector.get("sender").asText();
            String body = vector.get("body").asText();
            SmsFilter.Reason expected = SmsFilter.Reason.valueOf(vector.get("reason").asText());
            tests.add(DynamicTest.dynamicTest("rejects: " + name, () -> {
                SmsFilter.Decision decision = SmsFilter.check(sender, body);
                assertThat(decision.accepted()).as("%s -- was accepted", name).isFalse();
                // The reason is asserted, not just the verdict. Rejecting an OTP
                // because it happens to lack an amount would pass a looser test
                // and then break the moment a bank reworded its template.
                assertThat(decision.reason()).as("%s -- wrong grounds", name).isEqualTo(expected);
            }));
        }
        assertThat(tests).isNotEmpty();
        return tests;
    }

    @Test
    void everyRejectionReasonIsCoveredByTheCorpus() throws IOException {
        List<String> covered = new ArrayList<>();
        load().get("reject").forEach(vector -> covered.add(vector.get("reason").asText()));

        // A rule with no example is a rule nobody has checked. If a reason is
        // added to the filter, the corpus has to gain a message that triggers
        // it -- otherwise the mobile implementation could omit the rule
        // entirely and every shared test would still pass.
        assertThat(covered)
                .as("each rejection reason needs at least one worked example")
                .containsAll(SmsFilter.rejectionReasons().stream().map(Enum::name).toList());
    }
}
