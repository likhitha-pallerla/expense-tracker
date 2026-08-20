package com.expensetracker.api.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expensetracker.api.security.CurrentUser;

/**
 * Read-only. There is nothing to save: the score is a view of the ledger, and
 * the only way to change it is to change the money.
 *
 * <p>Mapped at {@code /api/financial-health} rather than {@code /api/health},
 * which is the service liveness probe. Two unrelated things called health is
 * confusing enough without them sharing a path.
 */
@RestController
@RequestMapping("/api/financial-health")
public class FinancialHealthController {

    private final FinancialHealthService health;

    public FinancialHealthController(FinancialHealthService health) {
        this.health = health;
    }

    @GetMapping
    public HealthReport report() {
        return health.report(CurrentUser.id());
    }
}
