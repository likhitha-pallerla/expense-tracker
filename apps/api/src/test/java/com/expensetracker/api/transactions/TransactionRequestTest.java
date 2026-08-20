package com.expensetracker.api.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TransactionRequestTest {

    private static TransactionRequest request(String kind, String direction, List<String> tags) {
        return new TransactionRequest(kind, direction, new BigDecimal("100.00"), null,
                Instant.parse("2025-01-01T00:00:00Z"), "Lunch", null, tags,
                null, null, null, null, null, null);
    }

    @Test
    void defaultsToExpenseWhenKindIsAbsent() {
        assertThat(request(null, null, null).resolvedKind()).isEqualTo(TransactionKind.EXPENSE);
    }

    /** An expense takes money out; income brings it in. */
    @Test
    void infersDirectionFromKind() {
        assertThat(request("expense", null, null).resolvedDirection())
                .isEqualTo(TransactionDirection.DEBIT);
        assertThat(request("income", null, null).resolvedDirection())
                .isEqualTo(TransactionDirection.CREDIT);
    }

    @Test
    void explicitDirectionWinsOverTheInferredOne() {
        assertThat(request("expense", "credit", null).resolvedDirection())
                .isEqualTo(TransactionDirection.CREDIT);
    }

    /** A transfer needs two accounts, which this payload cannot express. */
    @Test
    void rejectsTransferKind() {
        assertThatThrownBy(() -> request("transfer", null, null).resolvedKind())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("transfers");
    }

    @Test
    void rejectsUnknownKind() {
        assertThatThrownBy(() -> request("refund", null, null).resolvedKind())
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown kind");
    }

    @Test
    void defaultsCurrencyToInrAndUppercasesIt() {
        assertThat(request(null, null, null).currencyOrDefault()).isEqualTo("INR");

        TransactionRequest usd = new TransactionRequest(null, null, BigDecimal.ONE, "usd",
                Instant.now(), null, null, null, null, null, null, null, null, null);
        assertThat(usd.currencyOrDefault()).isEqualTo("USD");
    }

    @Test
    void dropsBlankAndDuplicateTags() {
        String[] tags = request(null, null, Arrays.asList("food", "  ", "food", " travel ", null))
                .tagsOrEmpty();

        assertThat(tags).containsExactly("food", "travel");
    }

    @Test
    void tagsDefaultToAnEmptyArray() {
        assertThat(request(null, null, null).tagsOrEmpty()).isEmpty();
    }

    /** Blank must become null or the partial unique index would treat "" as a reference. */
    @Test
    void normalizesBlankExternalRefToNull() {
        TransactionRequest blank = new TransactionRequest(null, null, BigDecimal.ONE, null,
                Instant.now(), null, null, null, null, null, null, "   ", null, null);

        assertThat(blank.externalRefOrNull()).isNull();
    }

    @Test
    void trimsExternalRef() {
        TransactionRequest ref = new TransactionRequest(null, null, BigDecimal.ONE, null,
                Instant.now(), null, null, null, null, null, null, "  UTR123  ", null, null);

        assertThat(ref.externalRefOrNull()).isEqualTo("UTR123");
    }
}
