package com.expensetracker.api.forecast;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks the balance forward one day at a time.
 *
 * <p>Pure on purpose: this is the part that is easy to get subtly wrong and
 * impossible to notice, so it is tested directly rather than through the
 * database.
 *
 * <p>Only confirmed charges move the line. A suspected series is a guess, and a
 * guess that drags the projected balance below zero would have someone cancel a
 * plan they did not need to cancel.
 */
final class Projection {

    private Projection() {
    }

    record Result(List<ForecastDay> days, LowPoint low, BigDecimal closing) {
    }

    static Result run(
            ForecastWindow window, BigDecimal opening, List<ExpectedCharge> charges) {

        BigDecimal[] in = new BigDecimal[window.days()];
        BigDecimal[] out = new BigDecimal[window.days()];
        int[] events = new int[window.days()];
        java.util.Arrays.fill(in, BigDecimal.ZERO);
        java.util.Arrays.fill(out, BigDecimal.ZERO);

        for (ExpectedCharge charge : charges) {
            if (!charge.confirmed() || !window.covers(charge.expectedOn())) continue;
            int day = window.indexOf(charge.expectedOn());
            events[day]++;
            if (charge.isIncome()) {
                in[day] = in[day].add(charge.amount());
            } else {
                out[day] = out[day].add(charge.amount());
            }
        }

        List<ForecastDay> days = new ArrayList<>(window.days());
        BigDecimal balance = opening;
        LocalDate lowDate = window.today();
        BigDecimal lowBalance = opening;
        int lowDay = 0;

        for (int day = 0; day < window.days(); day++) {
            balance = balance.add(in[day]).subtract(out[day]);
            LocalDate date = window.today().plusDays(day);
            days.add(new ForecastDay(date, balance, in[day], out[day], events[day]));

            // Strictly less than, so the *first* day it bottoms out is reported
            // rather than the last. Someone needs to know when the trouble
            // starts, not when it stops getting worse.
            if (balance.compareTo(lowBalance) < 0) {
                lowBalance = balance;
                lowDate = date;
                lowDay = day;
            }
        }

        BigDecimal shortfall = lowBalance.signum() < 0 ? lowBalance.negate() : BigDecimal.ZERO;
        return new Result(days, new LowPoint(lowDate, lowDay, lowBalance, shortfall), balance);
    }
}
