package com.expensetracker.api.parsing;

/**
 * A view over text that gives up after a fixed amount of work.
 *
 * <p>Parser rules are regular expressions loaded from the database and applied
 * to mail that anybody in the world can send you. Java's regex engine has no
 * timeout, so a pattern that backtracks badly runs until the process is killed.
 *
 * <p>Java 21 optimises away the textbook exponential cases — {@code (a+)+b} and
 * its relatives finish in polynomial time — but polynomial is not the same as
 * fast. A quadratic pattern against a long mail body is minutes of one core,
 * and mail bodies are attacker-controlled and can be megabytes. On the single
 * free instance this runs on, that is the whole service.
 *
 * <p>The regex engine reads its input through {@link #charAt}, so counting
 * those reads measures the work done regardless of how the backtracking is
 * structured. When the budget is spent the match is abandoned and the rule is
 * treated as not matching, which is the safe answer: a message we could not
 * read is reported as unread rather than silently accepted.
 */
final class Bounded implements CharSequence {

    /**
     * Enough for any real alert to be matched many times over, small enough
     * that a pathological pattern is stopped in microseconds rather than days.
     */
    static final int DEFAULT_BUDGET = 2_000_000;

    /** Thrown when a pattern spends the budget. Not an error in the input. */
    static final class BudgetExceeded extends RuntimeException {
        BudgetExceeded() {
            super("regex budget exceeded", null, false, false);
        }
    }

    private final CharSequence text;
    private final int start;
    private final int end;
    private final int[] budget;

    Bounded(CharSequence text) {
        this(text, 0, text.length(), new int[] { DEFAULT_BUDGET });
    }

    private Bounded(CharSequence text, int start, int end, int[] budget) {
        this.text = text;
        this.start = start;
        this.end = end;
        this.budget = budget;
    }

    @Override
    public int length() {
        return end - start;
    }

    @Override
    public char charAt(int index) {
        // The counter is shared with every subsequence, so a pattern cannot
        // escape its budget by making the engine work on a slice instead.
        if (--budget[0] <= 0) {
            throw new BudgetExceeded();
        }
        return text.charAt(start + index);
    }

    @Override
    public CharSequence subSequence(int from, int to) {
        return new Bounded(text, start + from, start + to, budget);
    }

    @Override
    public String toString() {
        return text.subSequence(start, end).toString();
    }
}
