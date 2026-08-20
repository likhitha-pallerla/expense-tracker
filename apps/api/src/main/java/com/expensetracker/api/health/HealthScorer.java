package com.expensetracker.api.health;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.expensetracker.api.health.HealthFacts.BudgetFact;

/**
 * Turns measurements into a financial health score.
 *
 * <p>Pure: no database, no clock, no configuration. Everything it needs arrives
 * in {@link HealthFacts}, which is what makes the opinions in here arguable
 * against a test rather than against production data.
 *
 * <h2>What is not scored is not counted</h2>
 *
 * <p>The single most important rule. A user with no credit card has no credit
 * risk; a user who has never opened the budgets page has not failed at
 * budgeting. Scoring either as zero would tell someone with a perfectly healthy
 * ledger that they are doing badly, and the suggested fix — take out a card,
 * create a budget — would have nothing to do with the number that moved. So an
 * unmeasurable driver is dropped and the remaining weights are renormalised.
 * The report carries {@code coverage} so the client can say how much of the
 * intended picture the score was built from.
 *
 * <p>The same rule cuts the other way: a driver is not scored at 100 for being
 * absent either. Nobody earns fifteen points for owning no credit card.
 *
 * <h2>Curves, not thresholds</h2>
 *
 * <p>Each driver is scored on a piecewise-linear curve through a handful of
 * points that a person can defend out loud — twenty per cent saved is the
 * textbook target, three months of cover is the usual floor, thirty per cent
 * utilisation is where lenders start to notice. Bands alone would make the
 * score jump by fifteen points because one coffee moved a ratio across a
 * boundary, and a number that lurches for no visible reason stops being
 * believed.
 */
public final class HealthScorer {

    /**
     * Below this there is history but not enough of it. Three transactions can
     * describe a fortnight of takeaway and nothing else; a diagnosis drawn from
     * them would either alarm or reassure at random.
     */
    static final int MIN_TRANSACTIONS = 8;

    /** Everything here is a monthly rate, so a partial month cannot anchor it. */
    static final int MIN_MONTHS = 1;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private HealthScorer() {
    }

    public static HealthReport score(HealthFacts facts) {
        if (facts.monthsObserved() < MIN_MONTHS || facts.transactionCount() < MIN_TRANSACTIONS) {
            return notEnoughYet(facts);
        }

        List<Draft> drafts = List.of(
                savingsRate(facts),
                cashBuffer(facts),
                creditUtilisation(facts),
                budgetDiscipline(facts),
                commitmentLoad(facts));

        int measuredWeight = drafts.stream().filter(d -> d.score != null)
                .mapToInt(d -> d.driver.weight()).sum();

        if (measuredWeight == 0) {
            return notEnoughYet(facts);
        }

        List<HealthSignal> signals = new ArrayList<>(drafts.size());
        double total = 0;
        for (Draft draft : drafts) {
            // Renormalising against the measured weight is what makes the score
            // "out of what we could see" rather than "out of everything we
            // wish we could see".
            int weight = draft.score == null
                    ? 0
                    : (int) Math.round(draft.driver.weight() * 100.0 / measuredWeight);
            if (draft.score != null) {
                total += draft.score * (draft.driver.weight() / (double) measuredWeight);
            }
            signals.add(new HealthSignal(
                    draft.driver.key(), draft.driver.label(), draft.score, weight,
                    draft.value, draft.unit, HealthSignal.bandOf(draft.score),
                    draft.finding, draft.action));
        }

        int score = (int) Math.round(total);
        String grade = gradeOf(score);

        return new HealthReport(
                score, grade, headline(grade, measuredWeight),
                measuredWeight, facts.monthsObserved(),
                facts.windowStart(), facts.windowEnd(), facts.currency(),
                signals, priorities(signals), wins(signals), missing(signals));
    }

    // ---- drivers -----------------------------------------------------------

    /**
     * What share of income survives the month.
     *
     * <p>Measured from income and spending rather than from the change in
     * balance, because a balance also moves when money is shifted between the
     * user's own accounts, and a transfer is not saving.
     */
    private static Draft savingsRate(HealthFacts facts) {
        BigDecimal income = facts.monthlyIncome();
        if (income == null || income.signum() <= 0) {
            return Draft.unmeasured(Driver.SAVINGS_RATE, "percent",
                    "No income recorded in this window.",
                    "Add your salary or other income so we can measure what you keep.");
        }

        BigDecimal kept = income.subtract(orZero(facts.monthlyExpense()));
        double rate = percent(kept, income);
        int score = curve(rate, -25, 0, -10, 15, 0, 40, 10, 70, 20, 90, 30, 100);

        String finding = kept.signum() >= 0
                ? "You keep %.1f%% of what you earn — about %s a month."
                        .formatted(rate, money(kept, facts.currency()))
                : "You spend about %s a month more than you earn."
                        .formatted(money(kept.abs(), facts.currency()));

        BigDecimal target = income.multiply(BigDecimal.valueOf(0.20));
        String action = kept.compareTo(target) >= 0
                ? "You are past the 20% mark. Holding this rate is what compounds."
                : "Freeing up %s a month would put you at a 20%% savings rate."
                        .formatted(money(target.subtract(kept), facts.currency()));

        return new Draft(Driver.SAVINGS_RATE, score, round(rate), "percent", finding, action);
    }

    /**
     * How long the money would last if income stopped.
     *
     * <p>Card debt is netted off the cash. Someone holding fifty thousand in
     * savings and forty thousand on a card does not have fifty thousand of
     * cover, and a buffer that ignores the debt is exactly the reassurance that
     * makes an emergency expensive.
     */
    private static Draft cashBuffer(HealthFacts facts) {
        BigDecimal spend = orZero(facts.monthlyExpense());
        if (spend.signum() <= 0) {
            return Draft.unmeasured(Driver.CASH_BUFFER, "months",
                    "No spending recorded in this window.",
                    "Import or add some transactions so we know what your cash has to cover.");
        }

        BigDecimal net = orZero(facts.liquidBalance()).subtract(orZero(facts.cardDebt()));
        double months = net.divide(spend, 2, RoundingMode.HALF_UP).doubleValue();
        int score = curve(months, 0, 0, 1, 40, 3, 75, 6, 100);

        String finding = net.signum() >= 0
                ? "Your cash covers about %.1f months of spending.".formatted(months)
                : "Card debt exceeds your cash by %s.".formatted(money(net.abs(), facts.currency()));

        double targetMonths = months >= 3 ? 6 : 3;
        BigDecimal gap = spend.multiply(BigDecimal.valueOf(targetMonths)).subtract(net);
        String action = months >= 6
                ? "Six months of cover is a comfortable place to be."
                : "Another %s would take you to %.0f months of cover."
                        .formatted(money(gap, facts.currency()), targetMonths);

        return new Draft(Driver.CASH_BUFFER, score, round(months), "months", finding, action);
    }

    /**
     * How much of the available credit is in use.
     *
     * <p>Zero utilisation scores full marks. Credit scoring models sometimes
     * penalise never touching a card, but that is an artefact of how lenders
     * assess risk, not a fact about whether someone's finances are sound.
     */
    private static Draft creditUtilisation(HealthFacts facts) {
        BigDecimal limit = facts.creditLimit();
        if (limit == null || limit.signum() <= 0) {
            return Draft.unmeasured(Driver.CREDIT_UTILISATION, "percent",
                    "No credit limit recorded.",
                    "Set a limit on your cards to track how much of your credit is in use.");
        }

        BigDecimal used = orZero(facts.cardOutstanding());
        double utilisation = percent(used, limit);
        int score = curve(utilisation, 10, 100, 30, 75, 50, 50, 75, 20, 100, 0);

        String finding = "You are using %.0f%% of a %s credit limit."
                .formatted(utilisation, money(limit, facts.currency()));

        BigDecimal comfortable = limit.multiply(BigDecimal.valueOf(0.30));
        String action = used.compareTo(comfortable) <= 0
                ? "Comfortably below the 30% mark lenders watch for."
                : "Paying down %s would bring you under 30%%."
                        .formatted(money(used.subtract(comfortable), facts.currency()));

        return new Draft(Driver.CREDIT_UTILISATION, score, round(utilisation), "percent",
                finding, action);
    }

    /**
     * Whether the limits the user set for themselves are holding.
     *
     * <p>Weighted by budget size, so blowing the twenty-thousand grocery budget
     * counts for more than overshooting a five-hundred coffee one. Statuses
     * come from the budgets module rather than being recomputed here, so the
     * two pages can never disagree about whether a budget is on track.
     */
    private static Draft budgetDiscipline(HealthFacts facts) {
        List<BudgetFact> live = facts.budgets().stream()
                .filter(b -> switch (b.status()) {
                    case "on_track", "warning", "over" -> true;
                    default -> false;
                })
                .toList();

        if (live.isEmpty()) {
            return Draft.unmeasured(Driver.BUDGET_DISCIPLINE, "count",
                    "No active budgets.",
                    "Set a budget or two — it is the fastest way to turn spending into a decision.");
        }

        BigDecimal weight = BigDecimal.ZERO;
        BigDecimal weighted = BigDecimal.ZERO;
        for (BudgetFact budget : live) {
            // A budget of zero would otherwise contribute nothing and silently
            // vanish from an average it belongs in.
            BigDecimal amount = budget.amount().max(BigDecimal.ONE);
            int points = switch (budget.status()) {
                case "on_track" -> 100;
                case "warning" -> 60;
                default -> 0;
            };
            weight = weight.add(amount);
            weighted = weighted.add(amount.multiply(BigDecimal.valueOf(points)));
        }

        int score = weighted.divide(weight, 2, RoundingMode.HALF_UP).intValue();
        long over = live.stream().filter(b -> "over".equals(b.status())).count();
        long onTrack = live.stream().filter(b -> "on_track".equals(b.status())).count();

        String finding = "%d of %d budgets are on track.".formatted(onTrack, live.size());
        String action = over == 0
                ? "Nothing is over its limit."
                : live.stream()
                        .filter(b -> "over".equals(b.status()))
                        .max(Comparator.comparing(BudgetFact::amount))
                        .map(b -> "%s is over its limit — the largest one you have blown."
                                .formatted(b.name()))
                        .orElse("Nothing is over its limit.");

        return new Draft(Driver.BUDGET_DISCIPLINE, score, (double) live.size(), "count",
                finding, action);
    }

    /**
     * How much of each month is spoken for before anything is decided.
     *
     * <p>This is what determines whether a bad month is survivable. Two people
     * with the same savings rate are in very different positions if one of them
     * can stop most of their spending and the other has already promised it.
     *
     * <p>Only confirmed series count. Detected-but-unconfirmed suggestions would
     * make the score move on its own as the detector changed its mind about a
     * merchant, which is not something the user did.
     */
    private static Draft commitmentLoad(HealthFacts facts) {
        BigDecimal income = facts.monthlyIncome();
        if (income == null || income.signum() <= 0) {
            return Draft.unmeasured(Driver.COMMITMENT_LOAD, "percent",
                    "No income recorded in this window.",
                    "Add your income so we can weigh fixed costs against it.");
        }
        if (facts.commitmentCount() == 0) {
            return Draft.unmeasured(Driver.COMMITMENT_LOAD, "percent",
                    "No confirmed recurring payments.",
                    "Confirm your subscriptions on the Recurring page to see what is already spoken for.");
        }

        BigDecimal commitments = orZero(facts.monthlyCommitments());
        double load = percent(commitments, income);
        int score = curve(load, 30, 100, 50, 70, 65, 40, 80, 15, 100, 0);

        String finding = "%d fixed commitments take %.0f%% of your income — %s a month."
                .formatted(facts.commitmentCount(), load, money(commitments, facts.currency()));

        BigDecimal comfortable = income.multiply(BigDecimal.valueOf(0.30));
        String action = commitments.compareTo(comfortable) <= 0
                ? "Under a third of your income is committed, which leaves room to move."
                : "Cutting %s of commitments would bring fixed costs under 30%% of income."
                        .formatted(money(commitments.subtract(comfortable), facts.currency()));

        return new Draft(Driver.COMMITMENT_LOAD, score, round(load), "percent", finding, action);
    }

    // ---- assembly ----------------------------------------------------------

    /**
     * Ordered by points recoverable, not by how bad each signal looks.
     *
     * <p>A driver sitting at 30 out of a weight of 15 is worth less attention
     * than one at 60 out of a weight of 30, and telling the user to fix the
     * first would be advice that barely moves the number they are being shown.
     */
    private static List<String> priorities(List<HealthSignal> signals) {
        return signals.stream()
                .filter(HealthSignal::measured)
                .filter(s -> s.pointsAvailable() >= 2)
                .sorted(Comparator.comparingInt(HealthSignal::pointsAvailable).reversed())
                .limit(3)
                .map(HealthSignal::action)
                .toList();
    }

    private static List<String> wins(List<HealthSignal> signals) {
        return signals.stream()
                .filter(s -> s.measured() && s.score() >= 80)
                .map(HealthSignal::finding)
                .toList();
    }

    private static List<String> missing(List<HealthSignal> signals) {
        return signals.stream()
                .filter(s -> !s.measured())
                .map(HealthSignal::action)
                .toList();
    }

    private static String gradeOf(int score) {
        if (score >= 80) {
            return "strong";
        }
        if (score >= 65) {
            return "good";
        }
        if (score >= 50) {
            return "fair";
        }
        return score >= 35 ? "needs_work" : "at_risk";
    }

    private static String headline(String grade, int coverage) {
        String base = switch (grade) {
            case "strong" -> "Your finances look resilient.";
            case "good" -> "You are in decent shape, with room to firm things up.";
            case "fair" -> "This holds together, but there is little margin for a surprise.";
            case "needs_work" -> "A few things need attention before a shock arrives.";
            default -> "Several signals are under strain at once.";
        };
        return coverage >= 100
                ? base
                : base + " Based on %d%% of the full picture.".formatted(coverage);
    }

    private static HealthReport notEnoughYet(HealthFacts facts) {
        List<String> missing = new ArrayList<>();
        if (facts.monthsObserved() < MIN_MONTHS) {
            missing.add("A score needs at least one complete calendar month of history.");
        }
        if (facts.transactionCount() < MIN_TRANSACTIONS) {
            missing.add("Only %d transactions so far — %d would be enough to start."
                    .formatted(facts.transactionCount(), MIN_TRANSACTIONS));
        }
        missing.add("Import a bank statement to backfill history quickly.");

        return new HealthReport(
                null, "unrated",
                "Not enough history yet to score anything honestly.",
                0, facts.monthsObserved(), facts.windowStart(), facts.windowEnd(),
                facts.currency(), List.of(), List.of(), List.of(), missing);
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * A piecewise-linear curve through {@code (x, score)} pairs given in
     * ascending x, clamped at both ends.
     */
    static int curve(double x, double... xy) {
        if (x <= xy[0]) {
            return (int) Math.round(xy[1]);
        }
        for (int i = 2; i < xy.length; i += 2) {
            if (x <= xy[i]) {
                double x0 = xy[i - 2];
                double y0 = xy[i - 1];
                double slope = (xy[i + 1] - y0) / (xy[i] - x0);
                return (int) Math.round(y0 + (x - x0) * slope);
            }
        }
        return (int) Math.round(xy[xy.length - 1]);
    }

    private static double percent(BigDecimal part, BigDecimal whole) {
        return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP).doubleValue();
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }

    /**
     * Whole units only. The rupees in "spend ₹4,213 less a month" are advice,
     * not accounting, and the paise make it harder to read without making it
     * any truer.
     */
    static String money(BigDecimal amount, String currency) {
        return com.expensetracker.api.web.Money.format(amount, currency);
    }

    /** A scored driver before renormalisation decides what its weight becomes. */
    private record Draft(Driver driver, Integer score, Double value, String unit,
            String finding, String action) {

        static Draft unmeasured(Driver driver, String unit, String finding, String action) {
            return new Draft(driver, null, null, unit, finding, action);
        }
    }
}
