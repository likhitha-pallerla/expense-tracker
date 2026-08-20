package com.expensetracker.api.insights;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.expensetracker.api.profile.UserSettings;
import com.expensetracker.api.security.CurrentUser;

/**
 * The dashboard's one question: how did this month go?
 */
@RestController
@RequestMapping("/api/insights")
public class InsightsController {

    /**
     * How far back a month may be asked for. Not a security limit — the rows are
     * the user's own — but a guard against a typed URL asking for the year 1200
     * and generating a pointless scan.
     */
    private static final int MAX_YEARS_BACK = 25;

    private final InsightsService insights;
    private final UserSettings settings;

    public InsightsController(InsightsService insights, UserSettings settings) {
        this.insights = insights;
        this.settings = settings;
    }

    /**
     * @param month {@code YYYY-MM}; defaults to the month the user is currently
     *              in, which is not necessarily the server's month
     */
    @GetMapping
    public Insights month(@RequestParam(required = false) String month) {
        java.util.UUID userId = CurrentUser.id();
        LocalDate today = settings.today(userId);
        return insights.forMonth(userId, parse(month, today));
    }

    private YearMonth parse(String month, LocalDate today) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(today);
        }
        YearMonth parsed;
        try {
            parsed = YearMonth.parse(month.strip());
        } catch (DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Give the month as YYYY-MM, for example 2026-03.");
        }

        YearMonth earliest = YearMonth.from(today).minusYears(MAX_YEARS_BACK);
        YearMonth latest = YearMonth.from(today).plusMonths(1);
        if (parsed.isBefore(earliest) || parsed.isAfter(latest)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That month is outside the range this can report on.");
        }
        return parsed;
    }
}
