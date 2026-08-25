package com.expensetracker.api.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Liveness for uptime checks and deploy scripts.
 *
 * <p>Anonymous, so it says as little as it can while still being useful. It
 * once returned {@code select version()} verbatim and the applied migration
 * count -- the exact Postgres build to the patch level, plus a rough age of the
 * deployment. That is reconnaissance handed to anyone who asks: it turns "is
 * there a known bug in their database" from research into a lookup. A checker
 * only needs to know whether the database answers.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("time", Instant.now().toString());

        try {
            // The cheapest question that still proves a connection can be
            // taken from the pool and a round trip completed.
            jdbc.queryForObject("select 1", Integer.class);
            body.put("database", "up");
        } catch (Exception ex) {
            // The class name only, never the message: driver exceptions quote
            // the host, port and database name back at you.
            log.warn("Health check could not reach the database", ex);
            body.put("status", "degraded");
            body.put("database", "down");
        }

        return body;
    }
}
