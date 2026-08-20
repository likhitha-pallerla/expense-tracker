package com.expensetracker.api.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class MerchantNormalizerTest {

    @Test
    void stripsPaymentRailNoise() {
        assertThat(MerchantNormalizer.normalize("POS SWIGGY")).isEqualTo("SWIGGY");
        assertThat(MerchantNormalizer.normalize("SWIGGY*ORDER 1234")).isEqualTo("SWIGGY ORDER");
    }

    /** Corporate suffixes carry no identity and must not cost a token. */
    @Test
    void stripsCorporateSuffixes() {
        assertThat(MerchantNormalizer.normalize("UPI-SWIGGY LTD")).isEqualTo("SWIGGY");
        assertThat(MerchantNormalizer.normalize("AMAZON PVT LIMITED")).isEqualTo("AMAZON");
        assertThat(MerchantNormalizer.normalize("ACME CORP")).isEqualTo("ACME");
    }

    /**
     * Adjacent noise tokens share a delimiter, so a single regex pass leaves
     * every second token behind. The normalizer must run to a fixed point.
     */
    @Test
    void stripsAdjacentNoiseTokens() {
        assertThat(MerchantNormalizer.normalize("UPI POS NEFT SWIGGY")).isEqualTo("SWIGGY");
    }

    @Test
    void collapsesVariantsOfTheSameMerchant() {
        String a = MerchantNormalizer.normalize("SWIGGY");
        String b = MerchantNormalizer.normalize("UPI-SWIGGY");
        String c = MerchantNormalizer.normalize("POS  SWIGGY  ");

        assertThat(a).isEqualTo(b).isEqualTo(c);
    }

    @Test
    void returnsNullWhenNothingMeaningfulRemains() {
        assertThat(MerchantNormalizer.normalize(null)).isNull();
        assertThat(MerchantNormalizer.normalize("   ")).isNull();
        assertThat(MerchantNormalizer.normalize("UPI")).isNull();
        assertThat(MerchantNormalizer.normalize("1234567")).isNull();
    }

    @Test
    void similarityIsOneForIdenticalNames() {
        assertThat(MerchantNormalizer.similarity("SWIGGY", "SWIGGY")).isEqualTo(1.0);
    }

    /** A strict subset is a strong match: "SWIGGY" inside "SWIGGY INSTAMART". */
    @Test
    void similarityIsHighWhenOneNameContainsTheOther() {
        assertThat(MerchantNormalizer.similarity("SWIGGY INSTAMART", "SWIGGY"))
                .isCloseTo(0.9, within(0.001));
    }

    @Test
    void containmentNeverOutranksAnExactMatch() {
        double contained = MerchantNormalizer.similarity("SWIGGY INSTAMART", "SWIGGY");
        double exact = MerchantNormalizer.similarity("SWIGGY", "SWIGGY");

        assertThat(contained).isLessThan(exact);
    }

    @Test
    void similarityIsPartialForPartiallySharedTokens() {
        assertThat(MerchantNormalizer.similarity("SWIGGY ONE", "SWIGGY TWO"))
                .isCloseTo(0.45, within(0.001));
    }

    @Test
    void similarityIsZeroForUnrelatedNames() {
        assertThat(MerchantNormalizer.similarity("SWIGGY", "AMAZON")).isEqualTo(0.0);
    }

    @Test
    void similarityHandlesMissingInput() {
        assertThat(MerchantNormalizer.similarity(null, "SWIGGY")).isEqualTo(0.0);
        assertThat(MerchantNormalizer.similarity("", "")).isEqualTo(0.0);
    }
}
