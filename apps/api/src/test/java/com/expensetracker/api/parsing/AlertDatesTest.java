package com.expensetracker.api.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AlertDatesTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Mail arrived on 12 August 2025, mid-afternoon in India. */
    private static final Instant RECEIVED =
            LocalDate.of(2025, 8, 12).atTime(15, 30).atZone(IST).toInstant();

    private static Instant noonOn(int year, int month, int day) {
        return LocalDate.of(year, month, day).atTime(LocalTime.NOON).atZone(IST).toInstant();
    }

    @Test
    @DisplayName("reads the day-first formats Indian banks use")
    void dayFirst() {
        assertThat(AlertDates.resolve("12-08-2025", RECEIVED, IST)).isEqualTo(noonOn(2025, 8, 12));
        assertThat(AlertDates.resolve("12/08/2025", RECEIVED, IST)).isEqualTo(noonOn(2025, 8, 12));
        assertThat(AlertDates.resolve("12-08-25", RECEIVED, IST)).isEqualTo(noonOn(2025, 8, 12));
        assertThat(AlertDates.resolve("12/08/25", RECEIVED, IST)).isEqualTo(noonOn(2025, 8, 12));
    }

    @Test
    @DisplayName("reads a written month")
    void writtenMonth() {
        assertThat(AlertDates.resolve("12-Aug-2025", RECEIVED, IST)).isEqualTo(noonOn(2025, 8, 12));
        assertThat(AlertDates.resolve("12 Aug 25", RECEIVED, IST)).isEqualTo(noonOn(2025, 8, 12));
    }

    @Test
    @DisplayName("reads ISO, which is unambiguous")
    void iso() {
        assertThat(AlertDates.resolve("2025-08-12", RECEIVED, IST)).isEqualTo(noonOn(2025, 8, 12));
    }

    @Test
    @DisplayName("reads dots as separators, which some banks use")
    void dotted() {
        assertThat(AlertDates.resolve("12.08.2025", RECEIVED, IST)).isEqualTo(noonOn(2025, 8, 12));
    }

    @Test
    @DisplayName("an ambiguous date is read day-first, as the bank meant it")
    void ambiguous() {
        Instant received = LocalDate.of(2025, 6, 6).atTime(9, 0).atZone(IST).toInstant();
        // 05/06/25 is the fifth of June here, not the sixth of May.
        assertThat(AlertDates.resolve("05/06/25", received, IST)).isEqualTo(noonOn(2025, 6, 5));
    }

    @Test
    @DisplayName("a date months from the mail is not believed")
    void implausiblyOld() {
        assertThat(AlertDates.resolve("12-01-2025", RECEIVED, IST)).isEqualTo(RECEIVED);
        assertThat(AlertDates.trusted("12-01-2025", RECEIVED, IST)).isFalse();
    }

    @Test
    @DisplayName("a date after the alert is not believed either")
    void inTheFuture() {
        assertThat(AlertDates.resolve("20-08-2025", RECEIVED, IST)).isEqualTo(RECEIVED);
    }

    @Test
    @DisplayName("a few days of settlement lag is normal and accepted")
    void settlementLag() {
        assertThat(AlertDates.resolve("09-08-2025", RECEIVED, IST)).isEqualTo(noonOn(2025, 8, 9));
        assertThat(AlertDates.trusted("09-08-2025", RECEIVED, IST)).isTrue();
    }

    @Test
    @DisplayName("no date at all falls back to when the mail arrived")
    void noDate() {
        assertThat(AlertDates.resolve(null, RECEIVED, IST)).isEqualTo(RECEIVED);
        assertThat(AlertDates.resolve("", RECEIVED, IST)).isEqualTo(RECEIVED);
        assertThat(AlertDates.trusted(null, RECEIVED, IST)).isFalse();
    }

    @Test
    @DisplayName("nonsense falls back rather than throwing")
    void nonsense() {
        assertThat(AlertDates.resolve("32-13-2025", RECEIVED, IST)).isEqualTo(RECEIVED);
        assertThat(AlertDates.resolve("yesterday", RECEIVED, IST)).isEqualTo(RECEIVED);
    }

    @Test
    @DisplayName("midday, so the day is right when read back in UTC")
    void middayNotMidnight() {
        // The database stores an instant and reports read it in the user's zone,
        // but anything careless reads it in UTC. Midnight IST would be half past
        // six the evening before in UTC and land on the wrong day; midday does
        // not. Zones further than twelve hours from the user's own are not
        // defended against, because nothing in the product renders in one.
        Instant parsed = AlertDates.resolve("12-08-2025", RECEIVED, IST);
        assertThat(parsed.atZone(IST).toLocalDate()).isEqualTo(LocalDate.of(2025, 8, 12));
        assertThat(parsed.atZone(ZoneId.of("UTC")).toLocalDate())
                .isEqualTo(LocalDate.of(2025, 8, 12));
    }
}
