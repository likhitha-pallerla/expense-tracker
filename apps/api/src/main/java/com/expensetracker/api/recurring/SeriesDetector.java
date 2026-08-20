package com.expensetracker.api.recurring;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Decides whether a run of charges at one merchant is a subscription.
 *
 * <p>Pure arithmetic on dates and amounts — no database, no clock. Detection is
 * the part most likely to be wrong in an interesting way, so it is kept
 * separate from everything that would make it hard to test.
 *
 * <p>The output carries its own reasons. A subscription list the user cannot
 * argue with is a list they will not trust, and "we found this because it has
 * been charged six times, always within a day of the 5th, always ₹499" is an
 * argument they can check.
 */
public final class SeriesDetector {

    /**
     * Two charges is a coincidence — one gap, and nothing to compare it with.
     * Three is the first point at which regularity means anything.
     */
    public static final int MIN_OCCURRENCES = 3;

    /** Below this, a suggestion is noise and is not offered at all. */
    public static final double MIN_CONFIDENCE = 0.60;

    private static final double MIN_REGULARITY = 0.75;

    /** Amounts within this fraction of each other count as "the same". */
    private static final BigDecimal SAME_AMOUNT = new BigDecimal("0.01");

    /** The spread at which amounts are treated as entirely unpredictable. */
    private static final double UNSTABLE_SPREAD = 0.10;

    private SeriesDetector() {
    }

    /** One charge in the ledger. */
    public record Charge(LocalDate on, BigDecimal amount) {
    }

    /**
     * A detected series.
     *
     * @param typicalAmount the middle amount, which is what "it costs" means
     * @param latestAmount  what it costs now — a price rise is the new normal
     * @param amountVaries  true when the charge is never quite the same, as a
     *                      utility bill is; the user should not be shown a
     *                      precise forecast of something inherently uncertain
     * @param priceChanged  true when a steady amount stepped to a new one
     * @param reasons       why this was detected, in the user's language
     */
    public record Series(
            Cadence cadence,
            int occurrences,
            LocalDate firstCharge,
            LocalDate lastCharge,
            int anchorDay,
            BigDecimal typicalAmount,
            BigDecimal latestAmount,
            boolean amountVaries,
            boolean priceChanged,
            LocalDate nextExpected,
            double confidence,
            List<String> reasons) {
    }

    public static Optional<Series> detect(List<Charge> charges) {
        List<Charge> timeline = collapseSameDay(charges);
        if (timeline.size() < MIN_OCCURRENCES) {
            return Optional.empty();
        }

        List<Long> gaps = gapsOf(timeline);
        Optional<Fit> best = bestFit(gaps);
        if (best.isEmpty()) {
            return Optional.empty();
        }

        Fit fit = best.get();
        List<BigDecimal> amounts = timeline.stream().map(Charge::amount).toList();

        LocalDate first = timeline.get(0).on();
        LocalDate last = timeline.get(timeline.size() - 1).on();
        int anchorDay = anchorDayOf(timeline);

        BigDecimal typical = median(amounts);
        BigDecimal latest = amounts.get(amounts.size() - 1);
        double spread = relativeSpread(amounts, typical);

        double regularity = fit.regularity();
        double timing = 1 - Math.min(1, fit.meanDrift() / fit.cadence().toleranceDays());
        double evidence = Math.min(1, (timeline.size() - 2) / 4.0);
        double stability = 1 - Math.min(1, spread / UNSTABLE_SPREAD);

        double confidence = round(
                0.40 * regularity + 0.25 * timing + 0.20 * evidence + 0.15 * stability);
        if (confidence < MIN_CONFIDENCE) {
            return Optional.empty();
        }

        boolean varies = spread > SAME_AMOUNT.doubleValue();

        return Optional.of(new Series(
                fit.cadence(),
                timeline.size(),
                first,
                last,
                anchorDay,
                typical,
                latest,
                varies,
                steppedUpOrDown(amounts),
                fit.cadence().advance(last, anchorDay),
                confidence,
                reasons(timeline.size(), fit, varies, typical)));
    }

    // ---- cadence fitting ---------------------------------------------------

    private record Fit(Cadence cadence, int fitting, int total, int atOneCycle, double meanDrift) {
        double regularity() {
            return (double) fitting / total;
        }
    }

    /**
     * The cadence that explains the most gaps, if any does.
     *
     * <p>Every cadence is scored rather than taking the median gap and looking
     * it up: a series with one missed cycle has a median that belongs to no
     * cadence at all, and would otherwise be thrown away for the one thing it
     * most obviously is.
     */
    private static Optional<Fit> bestFit(List<Long> gaps) {
        List<Fit> fits = new ArrayList<>();
        for (Cadence cadence : Cadence.values()) {
            int fitting = 0;
            int atOne = 0;
            long drift = 0;
            for (long gap : gaps) {
                Optional<Integer> cycles = cadence.cyclesIn(gap);
                if (cycles.isPresent()) {
                    fitting++;
                    drift += cadence.driftOf(gap);
                    if (cycles.get() == 1) {
                        atOne++;
                    }
                }
            }
            if (fitting == 0) {
                continue;
            }
            fits.add(new Fit(cadence, fitting, gaps.size(), atOne, (double) drift / fitting));
        }

        return fits.stream()
                .filter(f -> f.regularity() >= MIN_REGULARITY)
                // A run of gaps that are all two cycles long is not this
                // cadence with holes in it, it is a slower cadence this list
                // does not carry. Reporting it as monthly would promise the
                // user a charge next month that is never going to arrive.
                .filter(f -> f.atOneCycle() * 2 >= f.fitting())
                .min(Comparator.<Fit>comparingDouble(Fit::regularity).reversed()
                        .thenComparingDouble(Fit::meanDrift)
                        .thenComparingInt(f -> f.cadence().nominalDays()));
    }

    private static List<Long> gapsOf(List<Charge> timeline) {
        List<Long> gaps = new ArrayList<>(timeline.size() - 1);
        for (int i = 1; i < timeline.size(); i++) {
            gaps.add(ChronoUnit.DAYS.between(timeline.get(i - 1).on(), timeline.get(i).on()));
        }
        return gaps;
    }

    // ---- shape of the charges ---------------------------------------------

    /**
     * Two charges on the same day are one occurrence, however they were
     * recorded — a plan billed as fee plus tax arrives as two rows but is one
     * event, and a zero-day gap would fit no cadence at all.
     */
    private static List<Charge> collapseSameDay(List<Charge> charges) {
        Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
        for (Charge charge : charges) {
            if (charge == null || charge.on() == null || charge.amount() == null) {
                continue;
            }
            byDay.merge(charge.on(), charge.amount(), BigDecimal::add);
        }
        return byDay.entrySet().stream()
                .map(e -> new Charge(e.getKey(), e.getValue()))
                .toList();
    }

    /**
     * The day of the month the series really sits on.
     *
     * <p>The most common one, not the most recent: a charge that slipped from
     * the 1st to the 2nd because the 1st was a bank holiday has not moved the
     * subscription. Ties go to the latest charge's day, which is the
     * arrangement currently in force.
     */
    private static int anchorDayOf(List<Charge> timeline) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (Charge charge : timeline) {
            counts.merge(charge.on().getDayOfMonth(), 1, Integer::sum);
        }

        int lastDay = timeline.get(timeline.size() - 1).on().getDayOfMonth();
        int best = counts.values().stream().max(Integer::compareTo).orElse(0);

        if (counts.getOrDefault(lastDay, 0) == best) {
            return lastDay;
        }
        return counts.entrySet().stream()
                .filter(e -> e.getValue() == best)
                .map(Map.Entry::getKey)
                .min(Integer::compareTo)
                .orElse(lastDay);
    }

    // ---- amounts -----------------------------------------------------------

    static BigDecimal median(List<BigDecimal> values) {
        List<BigDecimal> sorted = values.stream().sorted().toList();
        int size = sorted.size();
        if (size % 2 == 1) {
            return sorted.get(size / 2);
        }
        return sorted.get(size / 2 - 1).add(sorted.get(size / 2))
                .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
    }

    /**
     * Mean distance from the middle amount, as a fraction of it.
     *
     * <p>Mean rather than range, so one unusual month does not make a
     * rock-steady subscription look erratic.
     */
    private static double relativeSpread(List<BigDecimal> amounts, BigDecimal median) {
        if (median.signum() == 0) {
            return 0;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            total = total.add(amount.subtract(median).abs());
        }
        return total.divide(BigDecimal.valueOf(amounts.size()), 6, RoundingMode.HALF_UP)
                .divide(median.abs(), 6, RoundingMode.HALF_UP)
                .doubleValue();
    }

    /**
     * True when a settled amount moved to a different one.
     *
     * <p>Deliberately narrow: everything before the last charge has to have
     * been steady. A bill that is different every month has not had a price
     * rise, it simply varies, and saying otherwise every single month would
     * teach the user to ignore the flag.
     */
    private static boolean steppedUpOrDown(List<BigDecimal> amounts) {
        if (amounts.size() < 3) {
            return false;
        }
        List<BigDecimal> earlier = amounts.subList(0, amounts.size() - 1);
        BigDecimal settled = median(earlier);
        if (settled.signum() == 0 || relativeSpread(earlier, settled) > SAME_AMOUNT.doubleValue()) {
            return false;
        }
        BigDecimal latest = amounts.get(amounts.size() - 1);
        return latest.subtract(settled).abs()
                .divide(settled.abs(), 6, RoundingMode.HALF_UP)
                .compareTo(SAME_AMOUNT) > 0;
    }

    // ---- explanation -------------------------------------------------------

    private static List<String> reasons(int occurrences, Fit fit, boolean varies,
            BigDecimal typical) {
        List<String> reasons = new ArrayList<>();
        reasons.add("Charged " + occurrences + " times");

        if (fit.fitting() == fit.total()) {
            reasons.add("Every charge arrived " + fit.cadence().label().replace('_', '-'));
        } else {
            reasons.add(fit.fitting() + " of " + fit.total() + " gaps were "
                    + fit.cadence().label().replace('_', '-'));
        }

        long drift = Math.round(fit.meanDrift());
        reasons.add(drift == 0
                ? "Always exactly on schedule"
                : "Within " + drift + (drift == 1 ? " day" : " days") + " of schedule");

        reasons.add(varies
                ? "Amount varies around " + typical.stripTrailingZeros().toPlainString()
                : "Always " + typical.stripTrailingZeros().toPlainString());

        return List.copyOf(reasons);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
