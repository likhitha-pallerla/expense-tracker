package com.expensetracker.api.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

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
            body.put("database", jdbc.queryForObject("select version()", String.class));
            Integer migrations = jdbc.queryForObject(
                    "select count(*) from flyway_schema_history where success = true", Integer.class);
            body.put("migrationsApplied", migrations);
        } catch (Exception ex) {
            body.put("status", "degraded");
            body.put("database", "unreachable: " + ex.getClass().getSimpleName());
        }

        return body;
    }
}
