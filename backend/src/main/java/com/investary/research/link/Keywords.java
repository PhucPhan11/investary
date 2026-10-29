package com.investary.research.link;

import java.text.Normalizer;
import java.util.Collection;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Accent-insensitive keyword matching, so {@code "Hủy nhận"} in link text and {@code "huy-nhan"}
 * in a URL slug match the same rule.
 */
public final class Keywords {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s_\\-/]+");

    private Keywords() {
    }

    /**
     * Lower-case, strip diacritics, map {@code đ} to {@code d} and collapse separators to single spaces.
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value.toLowerCase(Locale.ROOT).replace('đ', 'd'), Normalizer.Form.NFD);
        return SEPARATORS.matcher(COMBINING_MARKS.matcher(decomposed).replaceAll("")).replaceAll(" ").strip();
    }

    /**
     * @param normalizedText text already passed through {@link #normalize}
     * @param keywords       raw keywords; normalized before matching
     */
    public static boolean containsAny(String normalizedText, Collection<String> keywords) {
        String padded = " " + normalizedText + " ";
        for (String keyword : keywords) {
            if (padded.contains(" " + normalize(keyword) + " ") || (keyword.length() > 4 && normalizedText.contains(normalize(keyword)))) {
                return true;
            }
        }
        return false;
    }
}
