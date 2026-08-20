package com.expensetracker.api.recurring;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

/**
 * The rhythms a real subscription is billed on.
 *
 * <p>The list is deliberately short. Plans are sold by the week, month,
 * quarter, half-year or year, so the bands leave wide gaps between them: a
 * merchant charged every 45 days on average is not on a plan, they are a habit,
 * and admitting them would bury the genuine subscriptions in noise.
 *
 * <p>Daily is absent for the same reason — a charge that lands every day is a
 * commute or a coffee.
 */
public enum Cadence {

    WEEKLY("weekly", 7, 2, 0),
    FORTNIGHTLY("fortnightly", 14, 3, 0),
    // Calendar months are 28-31 days, so even a perfectly regular charge
    // varies by three days before any drift is added.
    MONTHLY("monthly", 30, 5, 1),
    QUARTERLY("quarterly", 91, 10, 3),
    HALF_YEARLY("half_yearly", 182, 15, 6),
    YEARLY("yearly", 365, 20, 12);

    private final String label;
    private final int nominalDays;
    private final int toleranceDays;
    private final int months;

    Cadence(String label, int nominalDays, int toleranceDays, int months) {
        this.label = label;
        this.nominalDays = nominalDays;
        this.toleranceDays = toleranceDays;
        this.months = months;
    }

    public String label() {
        return label;
    }

    public int nominalDays() {
        return nominalDays;
    }

    public int toleranceDays() {
        return toleranceDays;
    }

    /**
     * How many times a year this is billed.
     *
     * <p>Counted, not derived from {@link #nominalDays} — a monthly plan is
     * charged twelve times a year, and 365/30 would quietly bill it 12.2 times
     * and overstate every yearly total on the page.
     */
    public int chargesPerYear() {
        return switch (this) {
            case WEEKLY -> 52;
            case FORTNIGHTLY -> 26;
            case MONTHLY -> 12;
            case QUARTERLY -> 4;
            case HALF_YEARLY -> 2;
            case YEARLY -> 1;
        };
    }

    public static Optional<Cadence> from(String label) {
        if (label == null) {
            return Optional.empty();
        }
        String wanted = label.trim().toLowerCase();
        for (Cadence cadence : values()) {
            if (cadence.label.equals(wanted)) {
                return Optional.of(cadence);
            }
        }
        return Optional.empty();
    }

    /**
     * The cadence a stored {@code cadence_days} was saved as.
     *
     * <p>Nearest band rather than exact match, so a row written by hand still
     * lands somewhere sensible instead of being dropped.
     */
    public static Cadence nearest(int days) {
        Cadence best = MONTHLY;
        int bestDistance = Integer.MAX_VALUE;
        for (Cadence cadence : values()) {
            int distance = Math.abs(cadence.nominalDays - days);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = cadence;
            }
        }
        return best;
    }

    /**
     * How many whole cycles a gap of {@code days} spans, or empty when the gap
     * does not line up with this cadence at all.
     *
     * <p>A skipped cycle is still this cadence: a subscription billed on the
     * 5th that misses April is charged 61 days later and is still on the 5th.
     * The tolerance is therefore <em>not</em> widened for a longer gap — the
     * charge does not become sloppier just because one was missed.
     *
     * <p>Three cycles is the limit. A hole longer than that is not one series
     * with gaps, it is two series with a cancellation in between.
     */
    public Optional<Integer> cyclesIn(long days) {
        if (days <= 0) {
            return Optional.empty();
        }
        int cycles = (int) Math.round((double) days / nominalDays);
        if (cycles < 1 || cycles > 3) {
            return Optional.empty();
        }
        return Math.abs(days - (long) cycles * nominalDays) <= toleranceDays
                ? Optional.of(cycles)
                : Optional.empty();
    }

    /** How far a gap sits from the nearest whole number of cycles, in days. */
    public long driftOf(long days) {
        int cycles = Math.max(1, (int) Math.round((double) days / nominalDays));
        return Math.abs(days - (long) cycles * nominalDays);
    }

    /**
     * The next charge after {@code from}.
     *
     * <p>Month-length cadences advance by calendar months and land on
     * {@code anchorDay}, clamped to the length of the target month. Adding
     * {@link #nominalDays} instead would walk a monthly subscription backwards
     * through the calendar — 30 days after 31 January is 2 March.
     */
    public LocalDate advance(LocalDate from, int anchorDay) {
        if (months == 0) {
            return from.plusDays(nominalDays);
        }
        YearMonth target = YearMonth.from(from).plusMonths(months);
        return target.atDay(Math.min(Math.max(anchorDay, 1), target.lengthOfMonth()));
    }
}
