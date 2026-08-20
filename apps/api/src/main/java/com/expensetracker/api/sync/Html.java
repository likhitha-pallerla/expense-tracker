package com.expensetracker.api.sync;

import java.util.Locale;

/**
 * Reduces HTML mail to the words in it.
 *
 * <p>Bank alerts are mostly sent as HTML, and the tags are noise to everything
 * downstream: a parser looking for an amount should not have to step over a
 * table layout, and a body hash that includes markup changes whenever the bank
 * redesigns its template, splitting one alert into two "different" messages.
 *
 * <p>This is not a parser and does not try to be. It removes what is definitely
 * not content, converts the handful of entities that actually appear in money
 * mail, and inserts whitespace where a tag implied a break — {@code
 * <td>Rs 500</td><td>debited</td>} must not become {@code Rs 500debited}, or
 * every amount at the end of a cell fuses with the next word.
 */
final class Html {

    private Html() {
    }

    static String toText(String html) {
        if (html == null) {
            return null;
        }

        String text = html
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<head[^>]*>.*?</head>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|tr|li|h[1-6]|table)>", "\n")
                .replaceAll("<[^>]+>", " ");

        return entities(text)
                .replace('\u00a0', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\s*\\n\\s*", "\n")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    /**
     * Only the entities that turn up in payment mail.
     *
     * <p>A full entity table would be a liability rather than an asset here:
     * the rupee and pound signs matter because an amount is unreadable without
     * them, and {@code &amp;} matters because merchant names are full of it.
     * The rest can stay as they are; an unrecognised entity is ugly, not wrong.
     * {@code &amp;} is decoded last, otherwise a literal "{@code &amp;rs;}" in
     * the source would become a rupee sign that was never there.
     */
    private static String entities(String text) {
        return text
                .replaceAll("(?i)&nbsp;", " ")
                .replaceAll("(?i)&#8377;|&#x20b9;|&rupee;", "₹")
                .replaceAll("(?i)&pound;|&#163;", "£")
                .replaceAll("(?i)&euro;|&#8364;", "€")
                .replaceAll("(?i)&quot;|&#34;", "\"")
                .replaceAll("(?i)&#39;|&apos;|&rsquo;|&#8217;", "'")
                .replaceAll("(?i)&lt;|&#60;", "<")
                .replaceAll("(?i)&gt;|&#62;", ">")
                .replaceAll("(?i)&amp;|&#38;", "&");
    }

    /** Whether a body is HTML, for providers that do not say. */
    static boolean looksLikeHtml(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("<html") || lower.contains("<body")
                || lower.contains("<div") || lower.contains("<table")
                || lower.contains("<br") || lower.contains("<p>");
    }
}
