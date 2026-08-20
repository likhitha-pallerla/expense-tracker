package com.expensetracker.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AccountTypeTest {

    @Test
    void parsesCaseInsensitively() {
        assertThat(AccountType.from("bank")).isEqualTo(AccountType.BANK);
        assertThat(AccountType.from("  Credit_Card ")).isEqualTo(AccountType.CREDIT_CARD);
    }

    /** Clients naturally send "credit-card"; Postgres stores "credit_card". */
    @Test
    void acceptsHyphenatedInput() {
        assertThat(AccountType.from("credit-card")).isEqualTo(AccountType.CREDIT_CARD);
    }

    @Test
    void producesTheLowercaseFormPostgresStores() {
        assertThat(AccountType.CREDIT_CARD.dbValue()).isEqualTo("credit_card");
    }

    /** A 400 beats an opaque enum cast failure from the database. */
    @Test
    void rejectsUnknownTypeWithTheValidOptions() {
        assertThatThrownBy(() -> AccountType.from("crypto"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown account type")
                .hasMessageContaining("credit_card");
    }

    @Test
    void rejectsMissingType() {
        assertThatThrownBy(() -> AccountType.from(" "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("required");
    }

    @Test
    void everyTypeRoundTripsThroughItsDbValue() {
        for (AccountType type : AccountType.values()) {
            assertThat(AccountType.from(type.dbValue())).isEqualTo(type);
        }
    }

    @Test
    void requestDefaultsCurrencyAndOpeningBalance() {
        AccountRequest request = new AccountRequest(" Savings ", "bank", null, null, null, null, null);

        assertThat(request.normalizedName()).isEqualTo("Savings");
        assertThat(request.currencyOrDefault()).isEqualTo("INR");
        assertThat(request.openingBalanceOrZero()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** A credit card starts life owing money; forcing zero would misreport net worth. */
    @Test
    void requestAllowsNegativeOpeningBalance() {
        AccountRequest request = new AccountRequest("Card", "credit_card", "inr", "1234",
                new BigDecimal("-4500.00"), null, null);

        assertThat(request.openingBalanceOrZero()).isEqualByComparingTo(new BigDecimal("-4500.00"));
        assertThat(request.currencyOrDefault()).isEqualTo("INR");
    }
}
