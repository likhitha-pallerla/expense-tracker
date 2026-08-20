package com.expensetracker.api.profile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProfileServiceTest {

    @Test
    void derivesDisplayNameFromEmailLocalPart() {
        assertThat(ProfileService.defaultDisplayName("ravi.kumar@example.com"))
                .isEqualTo("ravi.kumar");
    }

    @Test
    void returnsNullWhenEmailIsAbsent() {
        assertThat(ProfileService.defaultDisplayName(null)).isNull();
        assertThat(ProfileService.defaultDisplayName("   ")).isNull();
    }

    @Test
    void handlesEmailWithoutAtSign() {
        assertThat(ProfileService.defaultDisplayName("weird")).isEqualTo("weird");
    }

    @Test
    void doesNotProduceEmptyNameForLeadingAtSign() {
        assertThat(ProfileService.defaultDisplayName("@example.com"))
                .isEqualTo("@example.com");
    }
}
