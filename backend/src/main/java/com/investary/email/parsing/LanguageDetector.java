package com.investary.email.parsing;

import java.text.Normalizer;

/**
 * Tells Vietnamese from English text by the share of letters carrying Vietnamese diacritics.
 * Deliberately simple: brokerage emails mix exactly these two languages.
 */
final class LanguageDetector {

    static final String VIETNAMESE = "vi";
    static final String ENGLISH = "en";

    private static final int MIN_LETTERS = 12;
    private static final double VIETNAMESE_RATIO = 0.04;

    private LanguageDetector() {
    }

    /**
     * @return {@code "vi"}, {@code "en"} or {@code null} when the text is too short to tell
     */
    static String detect(String text) {
        if (text == null) {
            return null;
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
        int letters = 0;
        int marked = 0;
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (c == 'đ' || c == 'Đ') {
                    marked++;
                }
            } else if (isVietnameseMark(c)) {
                marked++;
            }
        }
        if (letters < MIN_LETTERS) {
            return marked > 0 && letters > 0 ? VIETNAMESE : null;
        }
        return (double) marked / letters >= VIETNAMESE_RATIO ? VIETNAMESE : ENGLISH;
    }

    /** Combining marks used by Vietnamese: tones, breve, circumflex, horn and dot below. */
    private static boolean isVietnameseMark(char c) {
        return c == '̀' || c == '́' || c == '̃' || c == '̉' || c == '̣'
                || c == '̆' || c == '̂' || c == '̛';
    }
}
