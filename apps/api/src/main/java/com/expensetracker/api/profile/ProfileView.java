package com.expensetracker.api.profile;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Profile as returned to clients. */
public record ProfileView(
        UUID userId,
        String email,
        String displayName,
        String baseCurrency,
        String timezone,
        String locale,
        OffsetDateTime onboardedAt,
        boolean newlyProvisioned) {
}
