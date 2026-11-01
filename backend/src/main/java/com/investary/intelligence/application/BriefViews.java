package com.investary.intelligence.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.investary.intelligence.domain.BriefAiStatus;
import com.investary.intelligence.domain.BriefItemKind;
import com.investary.intelligence.domain.BriefJobOutcome;
import com.investary.intelligence.domain.BriefJobStatus;
import com.investary.intelligence.domain.BriefSectionKey;
import com.investary.intelligence.domain.BriefSourceRole;
import com.investary.intelligence.domain.BriefStatus;
import com.investary.intelligence.domain.BriefTrigger;
import com.investary.intelligence.domain.Relevance;
import com.investary.intelligence.domain.ResearchState;
import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.assistant.EvidenceStatus;

/**
 * Daily brief API views and the stored content model. Everything here comes from stored records or from
 * validated model text; no storage keys, prompts or provider payloads.
 */
public final class BriefViews {

    public static final String DISCLAIMER = "Bản tin tổng hợp nghiên cứu có trong kho tài liệu của bạn và dữ liệu danh mục bạn nhập. "
            + "Khuyến nghị của công ty chứng khoán là ý kiến của bên thứ ba; Investary không đưa ra khuyến nghị mua, bán hay "
            + "tín hiệu giao dịch, và không có giá thị trường thời gian thực.";

    private BriefViews() {
    }

    /**
     * Stored as {@code briefs.content}.
     *
     * @param newSources     sources that became searchable in the period (after deduplication)
     * @param pendingSources sources imported in the period that are not searchable yet
     */
    public record BriefContent(String portfolioName, int newSources, int pendingSources, List<BriefSection> sections) {
    }

    /**
     * @param note           fixed explanation shown with the section (e.g. "priorities to read, not buy signals")
     * @param evidenceStatus lowest status of the section's checked items, or null when nothing needs evidence
     */
    public record BriefSection(BriefSectionKey key, String title, String note, EvidenceStatus evidenceStatus, List<BriefItem> items) {
    }

    /**
     * One statement. {@code text} is plain text; citation markers such as "[S1]" refer to {@code citations}
     * of the brief.
     *
     * @param evidenceStatus validation result for SOURCE_CLAIM and AI_INTERPRETATION items, null otherwise
     * @param assessmentId   impact assessment the item refers to, if any
     */
    public record BriefItem(BriefItemKind kind, String text, EvidenceStatus evidenceStatus, List<String> citations, String symbol,
                            Relevance relevance, LocalDate publishedOn, String dateBasis, String brokerage, UUID assessmentId) {

        static BriefItem note(String text) {
            return new BriefItem(BriefItemKind.SYSTEM_NOTE, text, null, List.of(), null, null, null, null, null, null);
        }

        static BriefItem fact(String text) {
            return new BriefItem(BriefItemKind.PORTFOLIO_FACT, text, null, List.of(), null, null, null, null, null, null);
        }
    }

    public record Limitation(String code, String message) {
    }

    /**
     * A source a brief cited or included, built from the stored chunk.
     *
     * @param sourceId        document or email id; null when the source was deleted later
     * @param publishedOn     publication date, or the email sent date when {@code dateBasis} is EMAIL_SENT
     * @param importedAt      when the email was imported or the document stored
     * @param indexedAt       when the source first became searchable
     * @param oldPublication  received in the period but published well before it
     * @param lateArrival     imported before the period, searchable only during it
     * @param receivedVia     emails that attached or linked this document
     */
    public record BriefCitation(String label, BriefSourceRole role, KnowledgeSourceType sourceType, UUID sourceId, String title,
                                String brokerage, LocalDate publishedOn, String dateBasis, Instant sentAt, Instant importedAt,
                                Instant indexedAt, Integer pageStart, Integer pageEnd, String section, String location, String excerpt,
                                List<String> symbols, Relevance relevance, boolean oldPublication, boolean lateArrival,
                                List<UUID> receivedVia) {
    }

    /**
     * A pending or omitted source.
     *
     * @param state for pending sources: NOT_TRACKED, PENDING, PROCESSING, FAILED, SKIPPED or OUTDATED
     */
    public record BriefSourceRef(BriefSourceRole role, KnowledgeSourceType sourceType, UUID sourceId, String title, String brokerage,
                                 LocalDate publishedOn, String dateBasis, Instant sentAt, Instant importedAt, Instant indexedAt,
                                 String state) {
    }

    public record RevisionRef(UUID id, int revision, BriefTrigger triggerType, BriefStatus status, Instant generatedAt) {
    }

    public record BriefListItem(UUID id, UUID portfolioId, String portfolioName, BriefTrigger triggerType, BriefStatus status,
                                ResearchState researchState, Instant periodStart, Instant periodEnd, String zone, LocalDate businessDate,
                                boolean partialPeriod, Instant generatedAt, int revision, BriefAiStatus aiStatus,
                                EvidenceStatus evidenceStatus, int newSources, int pendingSources, String errorCode) {
    }

    /**
     * @param sourceCutoff  sources that became searchable after this instant are not in the brief
     * @param inputTokens   provider-reported prompt tokens, or null (never estimated)
     */
    public record BriefView(UUID id, UUID portfolioId, String portfolioName, String briefType, BriefTrigger triggerType,
                            BriefStatus status, ResearchState researchState, Instant periodStart, Instant periodEnd, String zone,
                            LocalDate businessDate, boolean partialPeriod, Instant generatedAt, Instant sourceCutoff, String language,
                            List<BriefSection> sections, List<BriefCitation> citations, List<BriefSourceRef> pendingSources,
                            List<BriefSourceRef> omittedSources, List<Limitation> limitations, BriefAiStatus aiStatus, String model,
                            String promptVersion, String configVersion, EvidenceStatus evidenceStatus, String errorCode,
                            String errorSummary, Integer inputTokens, Integer outputTokens, int revision, UUID rootBriefId,
                            UUID previousRevisionId, List<RevisionRef> revisions, String disclaimer) {
    }

    public record BriefJobView(UUID id, BriefTrigger triggerType, BriefJobStatus status, BriefJobOutcome outcome, UUID portfolioId,
                               Instant periodStart, Instant periodEnd, String zone, LocalDate businessDate, boolean partialPeriod,
                               UUID previousBriefId, int attempts, int maxAttempts, Instant nextAttemptAt, UUID briefId, String errorCode,
                               String errorSummary, Instant createdAt, Instant startedAt, Instant completedAt) {
    }
}
