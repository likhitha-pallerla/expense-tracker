package com.expensetracker.api.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;

import com.expensetracker.api.observability.RequestIdFilter;

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

    @Nested
    @DisplayName("the catch-all")
    class Unexpected {

        private final ApiExceptionHandler handler = new ApiExceptionHandler();

        @AfterEach
        void clearContext() {
            MDC.clear();
        }

        @Test
        @DisplayName("never repeats the exception's own message back to the caller")
        void neverLeaksTheMessage() {
            // The realistic shape of the thing being kept out: a driver
            // exception that names the host, the database and the credentials
            // in use. None of it helps the caller and all of it helps an
            // attacker.
            ProblemDetail problem = handler.onUnexpected(new IllegalStateException(
                    "FATAL: password authentication failed for user \"postgres\" "
                            + "at db.zswvxwvdjqsxxhexvdgs.supabase.co:5432"));

            assertThat(problem.getDetail())
                    .doesNotContain("postgres")
                    .doesNotContain("supabase.co")
                    .doesNotContain("password");
        }

        @Test
        @DisplayName("is a 500, so clients and monitoring both treat it as ours")
        void isAServerError() {
            assertThat(handler.onUnexpected(new RuntimeException("x")).getStatus())
                    .isEqualTo(500);
        }

        @Test
        @DisplayName("quotes the request id, which is what makes a report followable")
        void quotesTheRequestId() {
            MDC.put(RequestIdFilter.REQUEST_ID, "ab12cd34");

            ProblemDetail problem = handler.onUnexpected(new RuntimeException("boom"));

            assertThat(problem.getDetail()).contains("ab12cd34");
            assertThat(problem.getProperties()).containsEntry("requestId", "ab12cd34");
        }

        @Test
        @DisplayName("still answers when there is no request id to quote")
        void survivesWithoutARequestId() {
            // Reachable from a scheduled job or a filter that ran before the
            // one setting the id. An NPE thrown while handling an exception
            // replaces a useful 500 with a useless one.
            ProblemDetail problem = handler.onUnexpected(new RuntimeException("boom"));

            assertThat(problem.getDetail()).contains("Something went wrong");
            assertThat(problem.getProperties()).doesNotContainKey("requestId");
        }

        @Test
        @DisplayName("says something a person can act on rather than a status code")
        void readsLikeASentence() {
            MDC.put(RequestIdFilter.REQUEST_ID, "ff00ff00");

            assertThat(handler.onUnexpected(new RuntimeException()).getDetail())
                    .contains("Please try again")
                    .contains("if it keeps happening");
        }
    }
}
