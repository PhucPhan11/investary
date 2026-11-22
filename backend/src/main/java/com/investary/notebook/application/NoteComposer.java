package com.investary.notebook.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.assistant.CitationValidator;
import com.investary.knowledge.assistant.EvidenceStatus;
import com.investary.knowledge.assistant.TradingLanguage;
import com.investary.knowledge.search.ResearchAvailabilityService.SourceState;
import com.investary.notebook.application.NoteEvidenceCollector.NoteEvidence;
import com.investary.notebook.application.NoteEvidenceCollector.SourceGroup;
import com.investary.notebook.domain.NoteKind;

import tools.jackson.databind.JsonNode;

/**
 * Turns the model's JSON into note content. Pure and deterministic:
 * <ul>
 *   <li>every model text is checked by {@link CitationValidator} against only the labels it may cite (a source's own
 *       labels for a per-source view); items left with no valid citation are dropped;</li>
 *   <li>items phrased as trading instructions are dropped;</li>
 *   <li>source titles, publishers, dates and extracted ratings come from stored records, never from the model.</li>
 * </ul>
 * The evidence status can only go down: SUPPORTED, then PARTIAL when an item was dropped or weakened, INSUFFICIENT
 * when nothing is left.
 */
final class NoteComposer {

    static final int MAX_POINTS = 8;
    static final int MAX_FAQ = 6;
    static final int MAX_COMPARISONS = 4;
    private static final Pattern MARKERS = Pattern.compile("\\[\\s*S\\d+(?:\\s*[,;]\\s*S\\d+)*\\s*]");

    private NoteComposer() {
    }

    /**
     * @param ratings email id → the brokerage rating extracted from that email for the note's ticker
     */
    static Composition compose(NoteKind kind, String ticker, JsonNode node, NoteEvidence evidence, Map<UUID, Rating> ratings) {
        State state = new State(evidence.sourceText(), ticker == null ? "" : ticker);
        Object content = switch (kind) {
            case SUMMARY -> summary(node, state);
            case FAQ -> faq(node, state);
            case CONSENSUS, TIMELINE -> views(kind, ticker, node, evidence, ratings, state);
        };
        List<String> limitations = new ArrayList<>(evidence.limitations());
        limitations.addAll(state.warnings);
        EvidenceStatus status = state.items == 0 ? EvidenceStatus.INSUFFICIENT : state.status;
        return new Composition(content, List.copyOf(limitations), status, List.copyOf(state.cited));
    }

    /** Content for a note without evidence (no model call). */
    static Composition empty(NoteKind kind, String ticker, NoteEvidence evidence) {
        Object content = switch (kind) {
            case SUMMARY -> new SummaryContent(List.of());
            case FAQ -> new FaqContent(List.of());
            case CONSENSUS, TIMELINE -> new ViewsContent(ticker, List.of(), List.of(), List.of());
        };
        return new Composition(content, evidence.limitations(), EvidenceStatus.INSUFFICIENT, List.of());
    }

    private static SummaryContent summary(JsonNode node, State state) {
        List<Point> points = new ArrayList<>();
        for (JsonNode element : node.path("points")) {
            if (points.size() >= MAX_POINTS) {
                break;
            }
            String text = element.isString() ? element.asString("") : element.path("text").asString("");
            Point point = state.point(text, state.allLabels(), "Tóm tắt");
            if (point != null) {
                points.add(point);
            }
        }
        return new SummaryContent(points);
    }

    private static FaqContent faq(JsonNode node, State state) {
        List<FaqItem> items = new ArrayList<>();
        for (JsonNode element : node.path("items")) {
            if (items.size() >= MAX_FAQ) {
                break;
            }
            String question = MARKERS.matcher(element.path("question").asString("")).replaceAll("").strip();
            if (question.isEmpty()) {
                continue;
            }
            if (TradingLanguage.isInstruction(question)) {
                state.drop("Câu hỏi gợi ý: đã bỏ một câu hỏi vì có dạng lệnh giao dịch.");
                continue;
            }
            Point answer = state.point(element.path("answer").asString(""), state.allLabels(), "Câu hỏi gợi ý");
            if (answer != null) {
                items.add(new FaqItem(question, answer.text(), answer.evidenceStatus(), answer.labels()));
            }
        }
        return new FaqContent(items);
    }

    private static ViewsContent views(NoteKind kind, String ticker, JsonNode node, NoteEvidence evidence, Map<UUID, Rating> ratings,
                                      State state) {
        Map<String, String> viewByGroup = new LinkedHashMap<>();
        for (JsonNode element : node.path("views")) {
            String group = element.path("group").asString("").strip().toUpperCase(Locale.ROOT);
            viewByGroup.putIfAbsent(group, element.path("view").asString(""));
        }
        List<SourceEntry> entries = new ArrayList<>();
        for (SourceGroup group : evidence.groups()) {
            SourceState source = group.source();
            String raw = viewByGroup.get(group.id());
            Point view = raw == null ? null : state.point(raw, group.labels(), label(source));
            Rating rating = source.sourceType() == KnowledgeSourceType.EMAIL ? ratings.get(source.sourceId()) : null;
            if (view == null && rating == null) {
                continue;
            }
            if (view == null) {
                state.items++; // the extracted rating alone is a stored fact worth showing
            }
            entries.add(new SourceEntry(group.id(), source.sourceType(), source.sourceId(), source.title(), source.brokerage(),
                    NoteEvidenceCollector.date(source), dateBasis(source), rating == null ? null : rating.rating(),
                    rating == null ? null : rating.location(), view));
        }
        if (kind == NoteKind.CONSENSUS) {
            entries.sort(java.util.Comparator.comparing((SourceEntry e) -> e.brokerage() == null ? "￿" : e.brokerage().toLowerCase(Locale.ROOT))
                    .thenComparing(SourceEntry::date, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        }
        List<Point> agreements = kind == NoteKind.CONSENSUS ? comparisons(node.path("agreements"), "Điểm đồng thuận", state) : List.of();
        List<Point> disagreements = kind == NoteKind.CONSENSUS ? comparisons(node.path("disagreements"), "Điểm khác biệt", state) : List.of();
        return new ViewsContent(ticker, entries, agreements, disagreements);
    }

    private static List<Point> comparisons(JsonNode array, String context, State state) {
        List<Point> points = new ArrayList<>();
        for (JsonNode element : array) {
            if (points.size() >= MAX_COMPARISONS) {
                break;
            }
            String text = element.isString() ? element.asString("") : element.path("text").asString("");
            Point point = state.point(text, state.allLabels(), context);
            if (point != null) {
                points.add(point);
            }
        }
        return points;
    }

    private static String label(SourceState source) {
        return source.brokerage() != null ? source.brokerage() : "Nguồn";
    }

    /** PUBLISHED or EMAIL_SENT as stored at index time; IMPORTED when only the import date is known. */
    static String dateBasis(SourceState source) {
        if (source.publishedOn() != null && source.dateBasis() != null) {
            return source.dateBasis();
        }
        return source.sentAt() != null ? "EMAIL_SENT" : "IMPORTED";
    }

    /** Running validation state of one note. */
    private static final class State {
        private final Map<String, String> sources;
        private final String facts;
        private final List<String> warnings = new ArrayList<>();
        private final Set<String> cited = new LinkedHashSet<>();
        private EvidenceStatus status = EvidenceStatus.SUPPORTED;
        private int items;

        State(Map<String, String> sources, String facts) {
            this.sources = sources;
            this.facts = facts;
        }

        List<String> allLabels() {
            return List.copyOf(sources.keySet());
        }

        void drop(String warning) {
            warnings.add(warning);
            status = EvidenceStatus.PARTIAL;
        }

        /** Validates one model text; null when it was dropped. */
        Point point(String raw, List<String> labels, String context) {
            String value = raw == null ? "" : raw.strip();
            if (value.isEmpty()) {
                return null;
            }
            if (TradingLanguage.isInstruction(value)) {
                drop(context + ": đã bỏ một nhận định vì có dạng lệnh giao dịch.");
                return null;
            }
            Map<String, String> allowed = new LinkedHashMap<>();
            labels.forEach(label -> {
                if (sources.containsKey(label)) {
                    allowed.put(label, sources.get(label));
                }
            });
            CitationValidator.Result validated = CitationValidator.validate(value, EvidenceStatus.SUPPORTED, allowed, facts);
            validated.warnings().forEach(w -> warnings.add(context + ": " + w));
            if (validated.citedLabels().isEmpty()) {
                drop(context + ": đã bỏ một nhận định vì không có trích dẫn hợp lệ.");
                return null;
            }
            if (validated.status() != EvidenceStatus.SUPPORTED) {
                status = EvidenceStatus.PARTIAL;
            }
            items++;
            cited.addAll(validated.citedLabels());
            return new Point(validated.answer(), validated.status(), validated.citedLabels());
        }
    }

    /** A brokerage rating as extracted from an email ("Chờ mua"); the brokerage's statement, not Investary's. */
    record Rating(String rating, String location) {
    }

    /**
     * @param content     one of the content records below, stored as JSON
     * @param citedLabels labels cited anywhere in the note, in order of first use
     */
    record Composition(Object content, List<String> limitations, EvidenceStatus status, List<String> citedLabels) {
    }

    record Point(String text, EvidenceStatus evidenceStatus, List<String> labels) {
    }

    record SummaryContent(List<Point> points) {
    }

    record FaqItem(String question, String answer, EvidenceStatus evidenceStatus, List<String> labels) {
    }

    record FaqContent(List<FaqItem> items) {
    }

    /**
     * One source in a consensus or timeline note. Everything but {@code view} is copied from stored records.
     *
     * @param rating         the brokerage's rating as extracted from the email, if any
     * @param ratingLocation where in the email the rating was found
     * @param view           the model's validated one-line summary of this source's view, or null
     */
    record SourceEntry(String group, KnowledgeSourceType sourceType, UUID sourceId, String title, String brokerage, LocalDate date,
                       String dateBasis, String rating, String ratingLocation, Point view) {
    }

    /** CONSENSUS: entries grouped by brokerage with agreements and disagreements. TIMELINE: entries oldest first. */
    record ViewsContent(String ticker, List<SourceEntry> sources, List<Point> agreements, List<Point> disagreements) {
    }
}
