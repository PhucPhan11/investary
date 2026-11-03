package com.investary.journal.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.investary.document.application.DocumentService;
import com.investary.document.domain.Document;
import com.investary.email.application.EmailExceptions.EmailNotFoundException;
import com.investary.email.application.EmailQueryService;
import com.investary.email.application.EmailViews.EmailDetail;
import com.investary.intelligence.application.ImpactAssessmentService;
import com.investary.intelligence.application.IntelligenceExceptions.AssessmentNotFoundException;
import com.investary.intelligence.application.IntelligenceViews.AssessmentView;
import com.investary.journal.application.JournalExceptions.EvidenceSourceNotFoundException;
import com.investary.journal.domain.DecisionEvidence;
import com.investary.journal.domain.EvidenceKind;
import com.investary.knowledge.assistant.PromptBuilder;
import com.investary.knowledge.search.ChunkLookupService;
import com.investary.knowledge.search.ChunkLookupService.StoredChunk;
import com.investary.research.application.ResearchItemView;
import com.investary.research.application.ResearchQueryService;

/**
 * Looks up an evidence source in its owning module and copies what the user sees of it. Only existing sources are
 * accepted; a missing one is never replaced by a similar one.
 */
@Component
class EvidenceResolver {

    static final int EXCERPT_LENGTH = 1000;
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final DocumentService documents;
    private final EmailQueryService emails;
    private final ResearchQueryService research;
    private final ChunkLookupService chunks;
    private final ImpactAssessmentService assessments;

    EvidenceResolver(DocumentService documents, EmailQueryService emails, ResearchQueryService research, ChunkLookupService chunks,
                     ImpactAssessmentService assessments) {
        this.documents = documents;
        this.emails = emails;
        this.research = research;
        this.chunks = chunks;
        this.assessments = assessments;
    }

    /**
     * @throws EvidenceSourceNotFoundException if the source does not exist (or, for a citation, is not a current chunk)
     */
    Resolved resolve(EvidenceKind kind, UUID id) {
        return switch (kind) {
            case DOCUMENT -> {
                Document document = documents.findAll(List.of(id)).stream().findFirst()
                        .orElseThrow(() -> new EvidenceSourceNotFoundException(kind, id));
                yield new Resolved(null, document.getOriginalFilename(), document.getBrokerageCompany(), document.getPublishedOn(),
                        null, null);
            }
            case EMAIL -> {
                EmailDetail email;
                try {
                    email = emails.get(id);
                } catch (EmailNotFoundException e) {
                    throw new EvidenceSourceNotFoundException(kind, id);
                }
                yield new Resolved(email.contentSha256(), email.subject(), email.brokerage(), date(email.sentAt()), null, null);
            }
            case RESEARCH_ITEM -> {
                ResearchItemView item = research.researchItem(id).orElseThrow(() -> new EvidenceSourceNotFoundException(kind, id));
                yield new Resolved(null, item.title(), item.brokerage(), date(item.publishedAt()), item.sourceLocation(),
                        excerpt(item.content()));
            }
            case CITATION -> {
                StoredChunk chunk = chunks.chunk(id).orElseThrow(() -> new EvidenceSourceNotFoundException(kind, id));
                yield new Resolved(chunk.contentSha256(), chunk.title(), chunk.brokerage(), chunk.publishedOn(),
                        PromptBuilder.location(chunk.pageStart(), chunk.pageEnd(), chunk.section()), excerpt(chunk.content()));
            }
            case IMPACT_ASSESSMENT -> {
                AssessmentView assessment;
                try {
                    assessment = assessments.view(id);
                } catch (AssessmentNotFoundException e) {
                    throw new EvidenceSourceNotFoundException(kind, id);
                }
                String portfolio = assessment.snapshot() == null ? null : assessment.snapshot().portfolioName();
                yield new Resolved(null, "Impact assessment" + (portfolio == null ? "" : " · " + portfolio), null,
                        date(assessment.createdAt()), null, excerpt(assessment.overallSummary()));
            }
        };
    }

    /**
     * Whether each evidence's source still exists. A citation also needs its chunk to be current with the same text.
     */
    Map<UUID, Boolean> availability(Collection<DecisionEvidence> evidence) {
        List<UUID> chunkIds = evidence.stream()
                .filter(e -> e.getKind() == EvidenceKind.CITATION && e.reference() != null)
                .map(DecisionEvidence::reference)
                .toList();
        Map<UUID, String> current = chunks.currentHashes(chunkIds);
        Map<UUID, Boolean> result = new HashMap<>();
        for (DecisionEvidence e : evidence) {
            boolean available = e.reference() != null
                    && (e.getKind() != EvidenceKind.CITATION || e.getContentSha256() == null
                            || e.getContentSha256().equals(current.get(e.reference())));
            result.put(e.getId(), available);
        }
        return result;
    }

    static String excerpt(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String stripped = text.strip();
        return stripped.length() <= EXCERPT_LENGTH ? stripped : stripped.substring(0, EXCERPT_LENGTH - 1) + "…";
    }

    private static LocalDate date(Instant instant) {
        return instant == null ? null : instant.atZone(MARKET_ZONE).toLocalDate();
    }

    /** What is copied from a source when it is attached. */
    record Resolved(String contentSha256, String title, String publisher, LocalDate publishedOn, String location, String excerpt) {
    }
}
