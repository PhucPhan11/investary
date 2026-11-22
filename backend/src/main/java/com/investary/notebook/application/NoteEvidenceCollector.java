package com.investary.notebook.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.investary.knowledge.assistant.Citations;
import com.investary.knowledge.assistant.PromptBuilder.LabeledHit;
import com.investary.knowledge.search.ResearchAvailabilityService;
import com.investary.knowledge.search.ResearchAvailabilityService.SourceState;
import com.investary.knowledge.search.SearchHit;
import com.investary.notebook.domain.NoteKind;

/**
 * Builds a note's evidence package deterministically, with no model call: which sources, which chunks, which labels.
 * Per-ticker notes use only sources that name the ticker. Each source gets its first chunk before any source gets a
 * second one, so a long report cannot crowd the others out of the size budget.
 */
@Component
class NoteEvidenceCollector {

    private final ResearchAvailabilityService availability;
    private final NotebookProperties properties;

    NoteEvidenceCollector(ResearchAvailabilityService availability, NotebookProperties properties) {
        this.availability = availability;
        this.properties = properties;
    }

    NoteEvidence collect(List<SourceState> searchable, NoteKind kind, String ticker) {
        List<String> limitations = new ArrayList<>();
        List<SourceState> candidates = candidates(searchable, kind, ticker);
        if (candidates.isEmpty()) {
            limitations.add(ticker == null
                    ? "Sổ tay chưa có nguồn nào đã được lập chỉ mục."
                    : "Không có nguồn nào trong sổ tay nhắc đến mã " + ticker + ".");
            return new NoteEvidence(List.of(), List.of(), limitations);
        }
        List<SourceState> used = candidates.size() > properties.noteSources()
                ? candidates.subList(0, properties.noteSources()) : candidates;
        if (used.size() < candidates.size()) {
            limitations.add("Chỉ dùng " + used.size() + " trong " + candidates.size() + " nguồn phù hợp (giới hạn mỗi ghi chú); "
                    + "các nguồn còn lại không được đọc.");
        }

        List<String> preferred = ticker == null ? List.of() : List.of(ticker);
        List<List<SearchHit>> perSource = new ArrayList<>();
        for (SourceState source : used) {
            perSource.add(availability.chunks(source.knowledgeSourceId(), preferred, properties.chunksPerSource()));
        }
        List<SearchHit> interleaved = new ArrayList<>();
        for (int round = 0; round < properties.chunksPerSource(); round++) {
            for (List<SearchHit> hits : perSource) {
                if (round < hits.size()) {
                    interleaved.add(hits.get(round));
                }
            }
        }
        List<LabeledHit> labeled = Citations.label(interleaved, properties.maxContextChars());

        Map<UUID, List<String>> labelsBySource = new LinkedHashMap<>();
        labeled.forEach(hit -> labelsBySource.computeIfAbsent(hit.hit().sourceId(), id -> new ArrayList<>()).add(hit.label()));
        List<SourceGroup> groups = new ArrayList<>();
        int skipped = 0;
        for (SourceState source : used) {
            List<String> labels = labelsBySource.get(source.sourceId());
            if (labels == null) {
                skipped++;
                continue;
            }
            groups.add(new SourceGroup("G" + (groups.size() + 1), source, List.copyOf(labels)));
        }
        if (skipped > 0) {
            limitations.add(skipped + " nguồn không được đọc vì vượt quá giới hạn độ dài gửi cho mô hình.");
        }
        return new NoteEvidence(labeled, groups, limitations);
    }

    /** Sources for the note, newest first; chronological for a timeline. */
    static List<SourceState> candidates(List<SourceState> searchable, NoteKind kind, String ticker) {
        List<SourceState> result = new ArrayList<>(searchable.stream()
                .filter(SourceState::searchable)
                .filter(source -> ticker == null || ticker.equals(source.sourceTicker()) || source.tickers().contains(ticker))
                .toList());
        Comparator<SourceState> byDate = Comparator.comparing(NoteEvidenceCollector::date, Comparator.nullsFirst(Comparator.naturalOrder()));
        result.sort(kind == NoteKind.TIMELINE ? byDate : byDate.reversed());
        return result;
    }

    static java.time.LocalDate date(SourceState source) {
        if (source.publishedOn() != null) {
            return source.publishedOn();
        }
        java.time.Instant fallback = source.sentAt() != null ? source.sentAt() : source.importedAt();
        return fallback == null ? null : fallback.atZone(NoteService.ZONE).toLocalDate();
    }

    /**
     * @param labeled every labelled chunk sent to the model
     * @param groups  one group per source that has at least one labelled chunk, in note order
     */
    record NoteEvidence(List<LabeledHit> labeled, List<SourceGroup> groups, List<String> limitations) {

        boolean empty() {
            return labeled.isEmpty();
        }

        Map<String, String> sourceText() {
            Map<String, String> text = new LinkedHashMap<>();
            labeled.forEach(hit -> text.put(hit.label(), hit.hit().content()));
            return text;
        }
    }

    /** One source and the labels of its chunks; {@code id} (G1, G2, …) lets the model refer to the whole source. */
    record SourceGroup(String id, SourceState source, List<String> labels) {
    }
}
