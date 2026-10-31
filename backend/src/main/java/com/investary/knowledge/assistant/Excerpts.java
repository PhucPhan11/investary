package com.investary.knowledge.assistant;

import java.util.List;

import com.investary.research.link.Keywords;

/**
 * Picks the most relevant verbatim passage of a chunk for display in a citation.
 */
final class Excerpts {

    private Excerpts() {
    }

    static String best(String content, List<String> terms, int maxLength) {
        if (content.length() <= maxLength) {
            return content;
        }
        String[] lines = content.split("\n");
        int bestLine = 0;
        int bestScore = -1;
        for (int i = 0; i < lines.length; i++) {
            String folded = " " + Keywords.normalize(lines[i]) + " ";
            int score = 0;
            for (String term : terms) {
                if (folded.contains(" " + term + " ")) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestLine = i;
            }
        }
        StringBuilder excerpt = new StringBuilder(lines[bestLine]);
        for (int i = bestLine + 1; i < lines.length && excerpt.length() + lines[i].length() + 1 <= maxLength; i++) {
            excerpt.append('\n').append(lines[i]);
        }
        String text = excerpt.toString();
        if (text.length() > maxLength) {
            int cut = text.lastIndexOf(' ', maxLength - 1);
            text = text.substring(0, cut > maxLength / 2 ? cut : maxLength - 1) + "…";
        }
        return (bestLine > 0 ? "…" : "") + text;
    }
}
