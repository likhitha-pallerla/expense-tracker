package com.expensetracker.api.health;

/**
 * The things a financial health score is actually made of.
 *
 * <p>The weights are the opinion of the product, stated in one place rather
 * than scattered through the scoring code. They add up to 100 only when every
 * driver can be measured; when one cannot, its weight is removed and the rest
 * are renormalised, so the score always means "out of everything we could
 * see".
 *
 * <p>Savings rate carries the most because it is the one number that decides
 * whether next year is easier or harder than this one. The cash buffer is
 * close behind: it is what turns an emergency into an inconvenience. The
 * remaining three are the ways the first two get quietly eroded.
 */
public enum Driver {

    SAVINGS_RATE("savings_rate", "Savings rate", 30),
    CASH_BUFFER("cash_buffer", "Cash buffer", 25),
    CREDIT_UTILISATION("credit_utilisation", "Credit utilisation", 15),
    BUDGET_DISCIPLINE("budget_discipline", "Budget discipline", 15),
    COMMITMENT_LOAD("commitment_load", "Fixed commitments", 15);

    private final String key;
    private final String label;
    private final int weight;

    Driver(String key, String label, int weight) {
        this.key = key;
        this.label = label;
        this.weight = weight;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public int weight() {
        return weight;
    }
}
