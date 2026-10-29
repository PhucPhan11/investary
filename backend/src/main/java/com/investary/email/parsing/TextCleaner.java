package com.investary.email.parsing;

import java.util.regex.Pattern;

/**
 * Normalizes whitespace and removes invisible filler characters that email templates use to pad
 * preheaders (combining grapheme joiner, zero-width characters, soft hyphens, BOM). Visible
 * characters, numbers and punctuation are never changed.
 */
final class TextCleaner {

    private static final Pattern INVISIBLE = Pattern.compile("[\\u034F\\u00AD\\u200B-\\u200F\\u2060-\\u2064\\uFEFF\\u180E]");
    private static final Pattern HORIZONTAL_SPACE = Pattern.compile("[\\t\\x0B\\f\\r\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000 ]+");
    private static final Pattern SPACE_AROUND_NEWLINE = Pattern.compile(" *\\n *");
    private static final Pattern MANY_NEWLINES = Pattern.compile("\\n{3,}");

    private TextCleaner() {
    }

    /** Removes invisible filler characters only; whitespace is left as is. */
    static String removeInvisible(String text) {
        return text == null ? null : INVISIBLE.matcher(text).replaceAll("");
    }

    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String result = text.replace("\r\n", "\n");
        result = INVISIBLE.matcher(result).replaceAll("");
        result = HORIZONTAL_SPACE.matcher(result).replaceAll(" ");
        result = SPACE_AROUND_NEWLINE.matcher(result).replaceAll("\n");
        result = MANY_NEWLINES.matcher(result).replaceAll("\n\n");
        return result.strip();
    }
}
