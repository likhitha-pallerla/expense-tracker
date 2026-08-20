package com.expensetracker.api.parsing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loading and compiling the rules that read payment alerts.
 *
 * <p>Rules are data, so any of them can be malformed — a pattern that does not
 * compile, an extractor missing its group. A bad rule is skipped and logged
 * rather than thrown, because the alternative is that one broken row stops
 * every user's import. The person who can fix it is not the person whose mail
 * stopped arriving.
 */
@Component
public class ParserRules {

    private static final Logger log = LoggerFactory.getLogger(ParserRules.class);

    /**
     * A user's own rules come first regardless of priority, then built-ins by
     * priority. Someone who has written a rule for their bank has said
     * something we cannot infer, and it should not lose to a generic pattern
     * that happens to carry a lower number.
     */
    private static final String SQL = """
            select id, user_id, name, sender_pattern, match_pattern, extractors, priority
            from parser_rules
            where is_enabled = true and (user_id is null or user_id = ?)
            order by (user_id is null), priority, name
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ParserRules(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * @return every rule this user can use, most specific first. Loaded once
     *         per run rather than cached: a user who has just fixed a rule
     *         expects the next attempt to use it, and the list is small.
     */
    public List<ParserRule> forUser(UUID userId) {
        List<ParserRule> rules = new ArrayList<>();
        jdbc.query(SQL, rs -> {
            compile(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("name"),
                    rs.getString("sender_pattern"),
                    rs.getString("match_pattern"),
                    rs.getString("extractors"),
                    rs.getInt("priority")).ifPresent(rules::add);
        }, userId);
        return rules;
    }

    private java.util.Optional<ParserRule> compile(UUID id, UUID userId, String name,
            String senderPattern, String matchPattern, String extractors, int priority) {
        try {
            Map<String, Extractor> fields = readExtractors(extractors);
            if (fields.isEmpty()) {
                log.warn("Parser rule {} ({}) has no usable extractors; skipping", name, id);
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(new ParserRule(
                    id,
                    userId,
                    name,
                    senderPattern == null || senderPattern.isBlank()
                            ? null
                            : Pattern.compile(senderPattern, Pattern.CASE_INSENSITIVE),
                    Pattern.compile(matchPattern),
                    fields,
                    priority));
        } catch (IllegalArgumentException ex) {
            // Covers both a pattern that does not compile and extractors that
            // are not usable JSON. Named, because "a rule failed" is useless
            // when there are dozens.
            log.warn("Parser rule {} ({}) does not compile; skipping: {}", name, id,
                    ex.getMessage());
            return java.util.Optional.empty();
        }
    }

    private Map<String, Extractor> readExtractors(String raw) {
        Map<String, Extractor> fields = new HashMap<>();
        if (raw == null || raw.isBlank()) {
            return fields;
        }

        JsonNode root;
        try {
            root = json.readTree(raw);
        } catch (Exception ex) {
            throw new IllegalArgumentException("extractors is not valid JSON", ex);
        }

        root.fields().forEachRemaining(entry -> {
            JsonNode node = entry.getValue();
            String pattern = node.path("pattern").asText(null);
            if (pattern == null || pattern.isBlank()) {
                return;
            }
            Extractor.Kind kind;
            try {
                kind = Extractor.Kind.valueOf(
                        node.path("as").asText("text").toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                kind = Extractor.Kind.TEXT;
            }
            fields.put(entry.getKey(),
                    new Extractor(Pattern.compile(pattern), node.path("group").asInt(1), kind));
        });
        return fields;
    }
}
