package com.expensetracker.api.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Telling the user what a sync did")
class SyncRunViewTest {

    private static SyncRunView run(String status, int fetched, int stored, int skipped,
                                   boolean more, String error) {
        return new SyncRunView(UUID.randomUUID(), UUID.randomUUID(), "gmail",
                Instant.now(), Instant.now(), status, fetched, stored, skipped, more, error);
    }

    @Test
    @DisplayName("an empty mailbox says nothing new")
    void nothingAtAll() {
        assertThat(run("ok", 0, 0, 0, false, null).summary()).isEqualTo("Nothing new.");
    }

    @Test
    @DisplayName("a mailbox where everything was already imported says so explicitly")
    void allDuplicates() {
        // "Nothing new" alone would read as "nothing happened", and a user
        // who just paid for something would reasonably think it was missed.
        assertThat(run("ok", 5, 0, 5, false, null).summary())
                .isEqualTo("Nothing new — everything found was already imported.");
    }

    @Test
    @DisplayName("one alert is singular")
    void one() {
        assertThat(run("ok", 1, 1, 0, false, null).summary()).isEqualTo("1 new alert imported.");
    }

    @Test
    @DisplayName("several alerts are plural")
    void several() {
        assertThat(run("ok", 4, 3, 1, false, null).summary()).isEqualTo("3 new alerts imported.");
    }

    @Test
    @DisplayName("a partial run invites another go rather than looking finished")
    void moreToCome() {
        assertThat(run("ok", 200, 200, 0, true, null).summary())
                .isEqualTo("200 new alerts so far, with more still to check.");
    }

    @Test
    @DisplayName("a failure repeats the reason instead of counting to zero")
    void failed() {
        assertThat(run("failed", 0, 0, 0, false, "This mailbox needs to be reconnected.").summary())
                .isEqualTo("This mailbox needs to be reconnected.");
    }

    @Test
    @DisplayName("a failure with no reason still says something useful")
    void failedWithoutReason() {
        assertThat(run("failed", 0, 0, 0, false, null).summary())
                .isEqualTo("Could not check this mailbox.");
    }

    @Test
    @DisplayName("only an 'ok' run counts as successful")
    void okOnly() {
        assertThat(run("ok", 0, 0, 0, false, null).ok()).isTrue();
        assertThat(run("running", 0, 0, 0, false, null).ok()).isFalse();
        assertThat(run("failed", 0, 0, 0, false, null).ok()).isFalse();
    }
}
