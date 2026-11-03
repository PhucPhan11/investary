package com.investary.automation.application;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Checks an optional Gmail search query of a source rule. The query only narrows the Gmail-side
 * prefilter (it is ANDed with the sender and date terms); it may not widen the search into spam,
 * trash, chats or "anywhere".
 */
public final class GmailQueryValidator {

    public static final int MAX_LENGTH = 500;
    private static final Pattern WIDENING = Pattern.compile("(?i)(^|[\\s(\\-])(in|is|label):(anywhere|spam|trash|chats?)\\b");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");

    private GmailQueryValidator() {
    }

    /**
     * @return a problem description, or empty if the query is acceptable (blank counts as "no query")
     */
    public static Optional<String> problem(String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }
        if (query.length() > MAX_LENGTH) {
            return Optional.of("must be at most " + MAX_LENGTH + " characters");
        }
        if (CONTROL.matcher(query).find()) {
            return Optional.of("must not contain control characters or line breaks");
        }
        int depth = 0;
        boolean quoted = false;
        for (char c : query.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                if (--depth < 0) {
                    return Optional.of("has unbalanced parentheses");
                }
            }
        }
        if (quoted) {
            return Optional.of("has an unclosed quote");
        }
        if (depth != 0) {
            return Optional.of("has unbalanced parentheses");
        }
        if (WIDENING.matcher(query).find()) {
            return Optional.of("must not search spam, trash, chats or all mail");
        }
        return Optional.empty();
    }
}
