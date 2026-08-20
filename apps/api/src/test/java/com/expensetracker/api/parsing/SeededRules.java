package com.expensetracker.api.parsing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The built-in rules, read out of the migration that ships them.
 *
 * <p>Reading the SQL rather than restating the rules in Java is the whole point.
 * A copy would drift: someone would fix a pattern in the migration, the test
 * would keep passing against the old one, and the failure would only surface in
 * a user's inbox. This way the tests exercise exactly what is deployed.
 */
final class SeededRules {

    /**
     * The insert rows in {@code V12__parsing.sql}. They are written in a fixed
     * shape so that this can stay simple; if the shape changes, this throws
     * rather than silently testing nothing.
     */
    private static final Pattern ROW = Pattern.compile(
            "\\(null, '((?:[^']|'')+)', null, null, null,\\s*"
                    + "'((?:[^']|'')+)',\\s*"
                    + "'(\\{(?:[^']|'')*\\})'::jsonb, (\\d+)\\)",
            Pattern.DOTALL);

    private SeededRules() {
    }

    static List<ParserRule> load() {
        String sql = read("db/migration/V12__parsing.sql");
        ObjectMapper mapper = new ObjectMapper();

        List<ParserRule> rules = new ArrayList<>();
        Matcher matcher = ROW.matcher(sql);
        while (matcher.find()) {
            rules.add(new ParserRule(
                    UUID.nameUUIDFromBytes(matcher.group(1).getBytes(StandardCharsets.UTF_8)),
                    null,
                    unquote(matcher.group(1)),
                    null,
                    Pattern.compile(unquote(matcher.group(2))),
                    extractors(mapper, unquote(matcher.group(3))),
                    Integer.parseInt(matcher.group(4))));
        }

        if (rules.isEmpty()) {
            throw new IllegalStateException(
                    "No rules found in V12__parsing.sql. The insert shape changed and these "
                            + "tests are no longer testing the shipped rules.");
        }
        rules.sort((a, b) -> Integer.compare(a.priority(), b.priority()));
        return rules;
    }

    /** SQL doubles a quote to escape it. */
    private static String unquote(String value) {
        return value.replace("''", "'");
    }

    private static Map<String, Extractor> extractors(ObjectMapper mapper, String json) {
        Map<String, Extractor> fields = new HashMap<>();
        try {
            JsonNode root = mapper.readTree(json);
            root.fields().forEachRemaining(entry -> {
                JsonNode node = entry.getValue();
                fields.put(entry.getKey(), new Extractor(
                        Pattern.compile(node.path("pattern").asText()),
                        node.path("group").asInt(1),
                        Extractor.Kind.valueOf(
                                node.path("as").asText("text").toUpperCase(Locale.ROOT))));
            });
        } catch (IOException ex) {
            throw new IllegalStateException("A seeded rule has invalid extractor JSON", ex);
        }
        return fields;
    }

    private static String read(String resource) {
        try (var stream = SeededRules.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read " + resource, ex);
        }
    }
}
