package com.expensetracker.api.forecast;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.expensetracker.api.security.CurrentUser;

/**
 * The other half of the dashboard's question: what is coming?
 */
@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private final ForecastService forecast;

    public ForecastController(ForecastService forecast) {
        this.forecast = forecast;
    }

    /**
     * @param days how far to look ahead. Clamped rather than rejected — someone
     *             typing {@code days=5000} wants a long view, not an error, and
     *             the response says which window was actually used
     */
    @GetMapping
    public Forecast ahead(
            @RequestParam(required = false, defaultValue = "30") int days) {
        return forecast.forecast(CurrentUser.id(), days);
    }
}
