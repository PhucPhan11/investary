package com.investary.knowledge.assistant;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.investary.research.link.Keywords;

/**
 * Deterministic checks on a model answer against the evidence it was given. It cannot prove that an
 * answer is right, but it catches the failures that matter most for financial research:
 * <ul>
 *   <li>citation markers that do not refer to a retrieved source are removed;</li>
 *   <li>figures (prices, percentages, amounts) in a cited sentence must appear in a cited source;</li>
 *   <li>a cited sentence must share at least one content word with its source;</li>
 *   <li>figures in sentences without any citation are reported as unsupported;</li>
 *   <li>an answer without valid citations cannot be SUPPORTED or PARTIAL.</li>
 * </ul>
 */
public final class CitationValidator {

    private static final Pattern MARKER = Pattern.compile("\\[\\s*(S\\d+(?:\\s*[,;]\\s*S\\d+)*)\\s*]");
    private static final Pattern LABEL = Pattern.compile("S\\d+");
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+(?=\\S)|\\n+");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]{4,}");
    /** One-digit numbers ("3 rủi ro", "Quý 2") are too common to check meaningfully. */
    private static final int MIN_CHECKED_DIGITS = 2;

    private CitationValidator() {
    }

    /**
     * @param answer   model answer text
     * @param sources  label → verbatim source text that was sent to the model
     * @param question the user's question (figures it contains may be repeated without citation)
     */
    public static Result validate(String answer, EvidenceStatus modelStatus, Map<String, String> sources, String question) {
        List<String> warnings = new ArrayList<>();
        Set<String> invalid = new LinkedHashSet<>();
        Set<String> cited = new LinkedHashSet<>();

        // 1. Remove markers that point at sources the model was not given.
        StringBuilder cleaned = new StringBuilder();
        Matcher markers = MARKER.matcher(answer);
        while (markers.find()) {
            List<String> valid = new ArrayList<>();
            Matcher labels = LABEL.matcher(markers.group(1));
            while (labels.find()) {
                String label = labels.group();
                if (sources.containsKey(label)) {
                    valid.add(label);
                } else {
                    invalid.add(label);
                }
            }
            markers.appendReplacement(cleaned, Matcher.quoteReplacement(valid.isEmpty() ? "" : "[" + String.join(", ", valid) + "]"));
        }
        markers.appendTail(cleaned);
        String text = cleaned.toString().replaceAll("[ \\t]+([.,;:!?])", "$1").replaceAll("[ \\t]{2,}", " ").strip();
        EvidenceStatus cap = EvidenceStatus.SUPPORTED;
        if (!invalid.isEmpty()) {
            warnings.add("Đã loại bỏ trích dẫn không tồn tại trong nguồn được truy xuất: " + String.join(", ", invalid) + ".");
            cap = EvidenceStatus.PARTIAL;
        }

        // 2. Check each sentence against the sources it cites.
        Set<String> questionNumbers = numbers(question);
        for (String sentence : sentences(text)) {
            Set<String> labels = new LinkedHashSet<>();
            Matcher found = MARKER.matcher(sentence);
            while (found.find()) {
                Matcher label = LABEL.matcher(found.group(1));
                while (label.find()) {
                    labels.add(label.group());
                }
            }
            String claim = MARKER.matcher(sentence).replaceAll(" ");
            Set<String> figures = numbers(claim);
            figures.removeAll(questionNumbers);
            if (labels.isEmpty()) {
                if (!figures.isEmpty() && !isAssistantRemark(claim)) {
                    warnings.add("Số liệu không có trích dẫn: \"" + shorten(claim) + "\".");
                    cap = cap.atMost(EvidenceStatus.PARTIAL);
                }
                continue;
            }
            cited.addAll(labels);
            StringBuilder evidence = new StringBuilder();
            labels.forEach(label -> evidence.append(sources.get(label)).append('\n'));
            Set<String> evidenceNumbers = numbers(evidence.toString());
            List<String> missing = figures.stream().filter(figure -> !evidenceNumbers.contains(figure)).toList();
            if (!missing.isEmpty()) {
                warnings.add("Không tìm thấy số liệu " + String.join(", ", missing) + " trong nguồn " + String.join(", ", labels)
                        + ": \"" + shorten(claim) + "\".");
                cap = cap.atMost(EvidenceStatus.PARTIAL);
            }
            if (!sharesWord(claim, evidence.toString())) {
                warnings.add("Câu sau không khớp nội dung nguồn " + String.join(", ", labels) + ": \"" + shorten(claim) + "\".");
                cap = cap.atMost(EvidenceStatus.PARTIAL);
            }
        }

        EvidenceStatus status = modelStatus.atMost(cap);
        if (cited.isEmpty() && status != EvidenceStatus.INSUFFICIENT) {
            warnings.add("Câu trả lời không có trích dẫn hợp lệ nên không được coi là có căn cứ.");
            status = EvidenceStatus.INSUFFICIENT;
        }
        return new Result(text, status, List.copyOf(cited), List.copyOf(warnings));
    }

    /**
     * Splits into sentences; a marker written after the full stop ("... Chờ mua. [S1]") stays with its sentence.
     */
    static List<String> sentences(String text) {
        List<String> result = new ArrayList<>();
        for (String part : SENTENCE_BREAK.split(text)) {
            if (part.isBlank()) {
                continue;
            }
            if (!result.isEmpty() && MARKER.matcher(part).replaceAll("").isBlank()) {
                result.set(result.size() - 1, result.getLast() + " " + part);
            } else {
                result.add(part);
            }
        }
        return result;
    }

    /**
     * Figures normalised to their digits, so "45.000", "45,000" and "45000" compare equal.
     */
    static Set<String> numbers(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = NUMBER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("[.,]", "");
            if (digits.length() >= MIN_CHECKED_DIGITS) {
                result.add(digits.replaceFirst("^0+(?=\\d)", ""));
            }
        }
        return result;
    }

    private static boolean sharesWord(String claim, String evidence) {
        String foldedEvidence = " " + Keywords.normalize(evidence) + " ";
        Matcher words = WORD.matcher(Keywords.normalize(claim));
        boolean anyWord = false;
        while (words.find()) {
            anyWord = true;
            if (foldedEvidence.contains(words.group())) {
                return true;
            }
        }
        return !anyWord;
    }

    private static boolean isAssistantRemark(String sentence) {
        String folded = Keywords.normalize(sentence);
        return folded.startsWith("nhan xet cua tro ly") || folded.startsWith("assistant note");
    }

    private static String shorten(String text) {
        String value = text.strip();
        return value.length() <= 120 ? value : value.substring(0, 117) + "...";
    }

    /**
     * @param citedLabels valid labels in order of first use
     */
    public record Result(String answer, EvidenceStatus status, List<String> citedLabels, List<String> warnings) {
    }
}
