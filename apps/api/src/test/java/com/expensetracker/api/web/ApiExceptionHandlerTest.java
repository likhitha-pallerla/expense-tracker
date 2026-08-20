package com.expensetracker.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ApiExceptionHandlerTest {

    private static DataIntegrityViolationException violation(String message) {
        return new DataIntegrityViolationException("wrapper", new RuntimeException(message));
    }

    @Test
    void explainsDuplicateAccountNames() {
        String detail = ApiExceptionHandler.describe(
                violation("duplicate key value violates unique constraint \"accounts_name_unique\""));

        assertThat(detail).isEqualTo("You already have an account with that name.");
    }

    @Test
    void explainsDuplicateBankReferences() {
        String detail = ApiExceptionHandler.describe(
                violation("violates unique constraint \"transactions_external_ref_unique\""));

        assertThat(detail).contains("bank reference");
    }

    @Test
    void explainsDuplicateBudgets() {
        String detail = ApiExceptionHandler.describe(
                violation("duplicate key value violates unique constraint \"budgets_period_unique\""));

        assertThat(detail).isEqualTo("You already have a budget for that category and period.");
    }

    @Test
    void explainsDuplicateRecurringPayments() {
        String detail = ApiExceptionHandler.describe(
                violation("duplicate key value violates unique constraint \"recurring_match_key_unique\""));

        assertThat(detail).isEqualTo("You are already tracking that as a recurring payment.");
    }

    /** Raw Postgres text can leak schema details, so unknowns get a generic message. */
    @Test
    void fallsBackToAGenericMessageForUnknownConstraints() {
        String detail = ApiExceptionHandler.describe(
                violation("violates foreign key constraint \"some_internal_fk\""));

        assertThat(detail).isEqualTo("That change conflicts with existing data.")
                .doesNotContain("some_internal_fk");
    }

    @Test
    void survivesAMissingCauseMessage() {
        assertThat(ApiExceptionHandler.describe(new DataIntegrityViolationException("x", new RuntimeException())))
                .isEqualTo("That change conflicts with existing data.");
    }
}
