package com.investary.journal.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.journal.application.DecisionViews.DecisionAction;
import com.investary.journal.application.DecisionViews.DecisionDetail;
import com.investary.journal.application.DecisionViews.DecisionSummary;
import com.investary.journal.application.DecisionViews.EvidenceView;
import com.investary.journal.application.DecisionViews.LinkState;
import com.investary.journal.application.DecisionViews.RevisionView;
import com.investary.journal.application.DecisionViews.TransactionLinkView;
import com.investary.journal.application.JournalExceptions.DecisionNotFoundException;
import com.investary.journal.domain.DecisionContent;
import com.investary.journal.domain.DecisionEvidence;
import com.investary.journal.domain.DecisionStatus;
import com.investary.journal.domain.DecisionTransactionLink;
import com.investary.journal.domain.DecisionType;
import com.investary.journal.domain.InvestmentDecision;
import com.investary.journal.persistence.DecisionEvidenceRepository;
import com.investary.journal.persistence.DecisionRevisionRepository;
import com.investary.journal.persistence.DecisionSnapshotRepository;
import com.investary.journal.persistence.DecisionTransactionLinkRepository;
import com.investary.journal.persistence.InvestmentDecisionRepository;
import com.investary.portfolio.application.PortfolioQueryService;
import com.investary.portfolio.application.PortfolioViews.TransactionView;

import jakarta.persistence.criteria.Predicate;

import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only access to decisions, their snapshot, evidence, transaction links and revision history.
 */
@Service
@Transactional(readOnly = true)
public class DecisionQueryService {

    private final InvestmentDecisionRepository decisions;
    private final DecisionEvidenceRepository evidence;
    private final DecisionTransactionLinkRepository links;
    private final DecisionSnapshotRepository snapshots;
    private final DecisionRevisionRepository revisions;
    private final EvidenceResolver resolver;
    private final PortfolioQueryService portfolios;
    private final JsonMapper json;

    public DecisionQueryService(InvestmentDecisionRepository decisions, DecisionEvidenceRepository evidence,
                                DecisionTransactionLinkRepository links, DecisionSnapshotRepository snapshots,
                                DecisionRevisionRepository revisions, EvidenceResolver resolver, PortfolioQueryService portfolios,
                                JsonMapper json) {
        this.decisions = decisions;
        this.evidence = evidence;
        this.links = links;
        this.snapshots = snapshots;
        this.revisions = revisions;
        this.resolver = resolver;
        this.portfolios = portfolios;
        this.json = json;
    }

    public record DecisionFilter(UUID portfolioId, String ticker, LocalDate from, LocalDate to, DecisionStatus status,
                                 DecisionType type) {
    }

    public Page<DecisionSummary> list(DecisionFilter filter, Pageable pageable) {
        return decisions.findAll(specification(filter), pageable).map(DecisionQueryService::summary);
    }

    public DecisionDetail detail(UUID id) {
        InvestmentDecision decision = decisions.findById(id).orElseThrow(() -> new DecisionNotFoundException(id));
        DecisionContent c = decision.content();
        DecisionSnapshot snapshot = snapshots.findById(id).map(s -> json.readValue(s.getSnapshot(), DecisionSnapshot.class)).orElse(null);
        return new DecisionDetail(decision.getId(), c.portfolioId(), c.portfolioName(), c.instrumentId(), c.symbol(), c.decisionType(),
                decision.getStatus(), c.decisionDate(), c.title(), c.rationale(), c.expectedOutcome(), c.timeHorizon(),
                c.mainAssumptions(), c.risks(), c.invalidationConditions(), c.reviewDate(), c.notes(), c.intendedQuantity(),
                c.intendedAllocationPct(), c.intendedPrice(), decision.getFinalizedAt(), decision.getExecutedAt(),
                decision.getCancelledAt(), decision.getCancelReason(), decision.getVersion(), decision.getCreatedAt(),
                decision.getUpdatedAt(), snapshot, evidence(id), links(id), actions(decision), DecisionViews.DISCLAIMER);
    }

    public List<RevisionView> history(UUID id) {
        if (!decisions.existsById(id)) {
            throw new DecisionNotFoundException(id);
        }
        return revisions.findByDecisionIdOrderBySequenceAsc(id).stream()
                .map(r -> new RevisionView(r.getSequence(), r.getKind(), r.getReason(), r.getStatusAfter(), json.readTree(r.getChanges()),
                        r.getCreatedAt()))
                .toList();
    }

    static DecisionSummary summary(InvestmentDecision d) {
        DecisionContent c = d.content();
        return new DecisionSummary(d.getId(), c.portfolioId(), c.portfolioName(), c.instrumentId(), c.symbol(), c.decisionType(),
                d.getStatus(), c.decisionDate(), c.title(), c.reviewDate(), d.getVersion(), d.getCreatedAt(), d.getUpdatedAt());
    }

    static List<DecisionAction> actions(InvestmentDecision decision) {
        List<DecisionAction> actions = new ArrayList<>();
        boolean canLink = decision.getPortfolioId() != null;
        switch (decision.getStatus()) {
            case DRAFT -> actions.addAll(List.of(DecisionAction.EDIT, DecisionAction.FINALIZE, DecisionAction.CANCEL,
                    DecisionAction.ATTACH_EVIDENCE));
            case DECIDED -> actions.addAll(List.of(DecisionAction.AMEND, DecisionAction.EXECUTE, DecisionAction.CANCEL,
                    DecisionAction.ATTACH_EVIDENCE));
            case EXECUTED -> actions.addAll(List.of(DecisionAction.AMEND, DecisionAction.ATTACH_EVIDENCE));
            case CANCELLED -> canLink = false;
        }
        if (canLink) {
            actions.add(DecisionAction.LINK_TRANSACTION);
        }
        return actions;
    }

    private List<EvidenceView> evidence(UUID decisionId) {
        List<DecisionEvidence> all = evidence.findByDecisionIdOrderByAttachedAtAscIdAsc(decisionId);
        Map<UUID, Boolean> available = resolver.availability(all);
        return all.stream()
                .map(e -> new EvidenceView(e.getId(), e.getKind(), e.getRole(), e.getSourceId(), available.get(e.getId()), e.getTitle(),
                        e.getPublisher(), e.getPublishedOn(), e.getLocation(), e.getExcerpt(), e.getNote(), e.getAttachedAt(),
                        e.getRemovedAt(), e.getRemovedReason()))
                .toList();
    }

    private List<TransactionLinkView> links(UUID decisionId) {
        List<DecisionTransactionLink> all = links.findByDecisionIdOrderByLinkedAtAscIdAsc(decisionId);
        List<UUID> ids = all.stream().map(DecisionTransactionLink::getTransactionId).filter(Objects::nonNull).distinct().toList();
        Map<UUID, TransactionView> current = portfolios.transactions(ids).stream()
                .collect(Collectors.toMap(TransactionView::id, Function.identity()));
        return all.stream().map(link -> {
            TransactionView now = link.getTransactionId() == null ? null : current.get(link.getTransactionId());
            return new TransactionLinkView(link.getId(), link.getTransactionRef(), link.getPortfolioId(), link.getSymbol(),
                    link.getTransactionType(), link.getTradeDate(), link.getQuantity(), link.getPrice(), link.getNote(),
                    link.getLinkedAt(), link.getRemovedAt(), link.getRemovedReason(), state(link, now), now);
        }).toList();
    }

    static LinkState state(DecisionTransactionLink link, TransactionView now) {
        if (now == null) {
            return LinkState.DELETED;
        }
        boolean same = now.type().name().equals(link.getTransactionType()) && now.tradeDate().equals(link.getTradeDate())
                && sameNumber(now.quantity(), link.getQuantity()) && sameNumber(now.price(), link.getPrice());
        return same ? LinkState.CURRENT : LinkState.CHANGED;
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static Specification<InvestmentDecision> specification(DecisionFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (filter.portfolioId() != null) {
                predicates.add(cb.equal(root.get("portfolioId"), filter.portfolioId()));
            }
            if (filter.ticker() != null && !filter.ticker().isBlank()) {
                predicates.add(cb.equal(root.get("symbol"), filter.ticker().strip().toUpperCase(Locale.ROOT)));
            }
            if (filter.from() != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("decisionDate"), filter.from()));
            }
            if (filter.to() != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("decisionDate"), filter.to()));
            }
            if (filter.status() != null) {
                predicates.add(cb.equal(root.get("status"), filter.status()));
            }
            if (filter.type() != null) {
                predicates.add(cb.equal(root.get("decisionType"), filter.type()));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
