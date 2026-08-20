package com.expensetracker.api.transactions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class TransactionFilterTest {

    private static TransactionFilter.Compiled compile(TransactionFilter filter) {
        return filter.compile(UUID.randomUUID());
    }

    private static TransactionFilter blank() {
        return new TransactionFilter(null, null, null, null, null, null, null,
                null, null, null, 50, 0);
    }

    @Nested
    class Paging {

        @Test
        void defaultsToFiftyWhenLimitIsUnset() {
            TransactionFilter filter = new TransactionFilter(null, null, null, null, null, null, null,
                    null, null, null, 0, 0);

            assertThat(filter.limit()).isEqualTo(50);
        }

        /** One client must not be able to pull the whole table in a request. */
        @Test
        void capsLimitAtTwoHundred() {
            TransactionFilter filter = new TransactionFilter(null, null, null, null, null, null, null,
                    null, null, null, 100_000, 0);

            assertThat(filter.limit()).isEqualTo(200);
        }

        @Test
        void clampsNegativeOffsetToZero() {
            TransactionFilter filter = new TransactionFilter(null, null, null, null, null, null, null,
                    null, null, null, 50, -10);

            assertThat(filter.offset()).isZero();
        }
    }

    @Nested
    class Scoping {

        /** The API bypasses RLS, so the user filter is the isolation boundary. */
        @Test
        void alwaysScopesToTheUser() {
            UUID userId = UUID.randomUUID();
            TransactionFilter.Compiled compiled = blank().compile(userId);

            assertThat(compiled.where()).contains("t.user_id = ?");
            assertThat(compiled.args()).first().isEqualTo(userId);
        }

        @Test
        void hidesDeletedAndMergedRows() {
            TransactionFilter.Compiled compiled = compile(blank());

            assertThat(compiled.where())
                    .contains("t.deleted_at is null")
                    .contains("t.merged_into_id is null");
        }

        /** Excluded rows are noise in totals unless explicitly requested. */
        @Test
        void hidesExcludedRowsByDefault() {
            assertThat(compile(blank()).where()).contains("t.is_excluded = false");
        }

        @Test
        void includesExcludedRowsWhenAsked() {
            TransactionFilter filter = new TransactionFilter(null, null, null, null, null, null, null,
                    null, null, true, 50, 0);

            assertThat(compile(filter).where()).doesNotContain("t.is_excluded = false");
        }
    }

    @Nested
    class Binding {

        @Test
        void addsOneArgumentPerActiveFilter() {
            UUID userId = UUID.randomUUID();
            TransactionFilter filter = new TransactionFilter(
                    Instant.parse("2025-01-01T00:00:00Z"),
                    Instant.parse("2025-02-01T00:00:00Z"),
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "expense", null,
                    new BigDecimal("10.00"), new BigDecimal("500.00"),
                    null, 50, 0);

            TransactionFilter.Compiled compiled = filter.compile(userId);

            // user + from + to + account + category + merchant + kind + min + max
            assertThat(compiled.args()).hasSize(9);
            assertThat(compiled.where().chars().filter(ch -> ch == '?').count()).isEqualTo(9);
        }

        /** Placeholders and arguments must stay in lockstep or binding shifts. */
        @Test
        void placeholderCountMatchesArgumentCountForEveryFilter() {
            TransactionFilter filter = new TransactionFilter(
                    Instant.now(), Instant.now(), UUID.randomUUID(), UUID.randomUUID(),
                    UUID.randomUUID(), "income", "coffee",
                    BigDecimal.ONE, BigDecimal.TEN, true, 10, 5);

            TransactionFilter.Compiled compiled = compile(filter);

            assertThat(compiled.where().chars().filter(ch -> ch == '?').count())
                    .isEqualTo(compiled.args().size());
        }

        /** Wildcards belong in the bound value so the pattern stays data. */
        @Test
        void wrapsSearchTermInWildcardsAsABoundValue() {
            TransactionFilter filter = new TransactionFilter(null, null, null, null, null, null,
                    "swiggy", null, null, null, 50, 0);

            TransactionFilter.Compiled compiled = compile(filter);

            assertThat(compiled.args()).contains("%swiggy%");
            assertThat(compiled.where()).contains("ilike ?").doesNotContain("swiggy");
        }

        @Test
        void searchIsBoundOncePerSearchableColumn() {
            TransactionFilter filter = new TransactionFilter(null, null, null, null, null, null,
                    "uber", null, null, null, 50, 0);

            assertThat(compile(filter).args()).filteredOn("%uber%"::equals).hasSize(3);
        }

        @Test
        void ignoresBlankSearch() {
            TransactionFilter filter = new TransactionFilter(null, null, null, null, null, null,
                    "   ", null, null, null, 50, 0);

            assertThat(compile(filter).where()).doesNotContain("ilike");
        }

        @Test
        void rejectsUnknownKind() {
            TransactionFilter filter = new TransactionFilter(null, null, null, null, null,
                    "refund", null, null, null, null, 50, 0);

            assertThatThrownBy(() -> compile(filter))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Unknown kind");
        }
    }
}
