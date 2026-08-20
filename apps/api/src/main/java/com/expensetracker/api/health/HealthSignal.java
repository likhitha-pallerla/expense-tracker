package com.expensetracker.api.health;

/**
 * One driver of the score, with its working shown.
 *
 * <p>A single number tells a user where they stand and nothing about what to
 * do, so every signal carries the measurement it came from, a plain sentence
 * saying what that measurement means, and the one action most likely to move
 * it. A score without a next step is just a grade.
 *
 * @param score  0–100, or null when the data to measure this does not exist
 * @param weight the weight actually applied — zero for an unmeasured signal,
 *               and higher than the driver's nominal weight when others dropped
 *               out and the remainder were renormalised
 * @param value  the raw measurement, so clients can format it themselves
 * @param unit   {@code percent}, {@code months} or {@code count}
 * @param band   strong, good, fair, weak, poor, or unknown
 * @param action what to do next, or why this could not be scored
 */
public record HealthSignal(
        String key,
        String label,
        Integer score,
        int weight,
        Double value,
        String unit,
        String band,
        String finding,
        String action) {

    static String bandOf(Integer score) {
        if (score == null) {
            return "unknown";
        }
        if (score >= 80) {
            return "strong";
        }
        if (score >= 60) {
            return "good";
        }
        if (score >= 40) {
            return "fair";
        }
        return score >= 20 ? "weak" : "poor";
    }

    /** How many points of the final score this signal is currently giving away. */
    int pointsAvailable() {
        return score == null ? 0 : (int) Math.round(weight * (100 - score) / 100.0);
    }

    boolean measured() {
        return score != null;
    }
}
