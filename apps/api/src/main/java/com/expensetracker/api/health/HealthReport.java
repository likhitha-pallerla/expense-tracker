package com.expensetracker.api.health;

import java.time.LocalDate;
import java.util.List;

/**
 * The financial health score and the reasoning behind it.
 *
 * <p>{@code score} is null rather than zero when there is too little history to
 * say anything. Inventing a number from three transactions would be worse than
 * useless: the user would either be alarmed by noise or reassured by it.
 *
 * <p>{@code coverage} is the share of the intended weighting that could
 * actually be measured. A 72 built from every driver and a 72 built from one
 * are not the same claim, and the client is given what it needs to say so.
 *
 * @param grade      strong, good, fair, needs_work, at_risk — or unrated
 * @param priorities the actions worth taking, ordered by how many points of
 *                   score each would recover rather than by how bad each
 *                   signal looks
 * @param wins       what is already going well, because a list of only
 *                   failures gets closed and not read
 * @param missing    what to record or set up to make the score more complete
 */
public record HealthReport(
        Integer score,
        String grade,
        String headline,
        int coverage,
        int monthsObserved,
        LocalDate windowStart,
        LocalDate windowEnd,
        String currency,
        List<HealthSignal> signals,
        List<String> priorities,
        List<String> wins,
        List<String> missing) {
}
