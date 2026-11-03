package com.investary.journal.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.investor.application.InvestorQueryService;
import com.investary.journal.application.DecisionCommands.DecisionCommand;
import com.investary.journal.application.DecisionCommands.EvidenceCommand;
import com.investary.journal.application.DecisionCommands.LinkCommand;
import com.investary.journal.application.DecisionCommands.TransitionCommand;
import com.investary.journal.application.DecisionViews.DecisionDetail;
import com.investary.journal.application.EvidenceResolver.Resolved;
import com.investary.journal.application.JournalExceptions.DecisionConflictException;
import com.investary.journal.application.JournalExceptions.DecisionNotFoundException;
import com.investary.journal.application.JournalExceptions.DuplicateEvidenceException;
import com.investary.journal.application.JournalExceptions.DuplicateTransactionLinkException;
import com.investary.journal.application.JournalExceptions.EvidenceNotFoundException;
import com.investary.journal.application.JournalExceptions.FieldLockedException;
import com.investary.journal.application.JournalExceptions.InvalidDecisionException;
import com.investary.journal.application.JournalExceptions.InvalidTransactionLinkException;
import com.investary.journal.application.JournalExceptions.InvalidTransitionException;
import com.investary.journal.application.JournalExceptions.TransactionLinkNotFoundException;
import com.investary.journal.domain.DecisionContent;
import com.investary.journal.domain.DecisionEvidence;
import com.investary.journal.domain.DecisionPortfolioSnapshot;
import com.investary.journal.domain.DecisionRevision;
import com.investary.journal.domain.DecisionStatus;
import com.investary.journal.domain.DecisionTransactionLink;
import com.investary.journal.domain.DecisionType;
import com.investary.journal.domain.InvestmentDecision;
import com.investary.journal.domain.RevisionKind;
import com.investary.journal.persistence.DecisionEvidenceRepository;
import com.investary.journal.persistence.DecisionRevisionRepository;
import com.investary.journal.persistence.DecisionSnapshotRepository;
import com.investary.journal.persistence.DecisionTransactionLinkRepository;
import com.investary.journal.persistence.InvestmentDecisionRepository;
import com.investary.portfolio.application.PortfolioExceptions.TransactionNotFoundException;
import com.investary.portfolio.application.PortfolioQueryService;
import com.investary.portfolio.application.PortfolioViews.Allocation;
import com.investary.portfolio.application.PortfolioViews.PortfolioSnapshot;
import com.investary.portfolio.application.PortfolioViews.PriceView;
import com.investary.portfolio.application.PortfolioViews.TransactionView;
import com.investary.portfolio.domain.TransactionType;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes to the decision journal. Every change checks the version the user saw, bumps the decision's version and
 * appends a revision. Portfolio and investor data are only read (query services): the journal never creates,
 * changes or infers transactions.
 */
@Service
@Transactional
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);

    private final InvestmentDecisionRepository decisions;
    private final DecisionRevisionRepository revisions;
    private final DecisionEvidenceRepository evidence;
    private final DecisionTransactionLinkRepository links;
    private final DecisionSnapshotRepository snapshots;
    private final PortfolioQueryService portfolios;
    private final InvestorQueryService investor;
    private final EvidenceResolver resolver;
    private final DecisionQueryService queries;
    private final JsonMapper json;
    private final Clock clock;

    public DecisionService(InvestmentDecisionRepository decisions, DecisionRevisionRepository revisions,
                           DecisionEvidenceRepository evidence, DecisionTransactionLinkRepository links,
                           DecisionSnapshotRepository snapshots, PortfolioQueryService portfolios, InvestorQueryService investor,
                           EvidenceResolver resolver, DecisionQueryService queries, JsonMapper json, Clock clock) {
        this.decisions = decisions;
        this.revisions = revisions;
        this.evidence = evidence;
        this.links = links;
        this.snapshots = snapshots;
        this.portfolios = portfolios;
        this.investor = investor;
        this.resolver = resolver;
        this.queries = queries;
        this.json = json;
        this.clock = clock;
    }

    public DecisionDetail create(DecisionCommand command) {
        Instant now = clock.instant();
        DecisionContent content = content(command, null);
        InvestmentDecision decision = decisions.saveAndFlush(new InvestmentDecision(content, now));
        revise(decision, RevisionKind.CREATED, null, diff(DecisionContent.empty().asMap(), content.asMap()), now);
        log.info("Decision created id={} type={}", decision.getId(), content.decisionType());
        return queries.detail(decision.getId());
    }

    /** Edits a draft, or amends a finalized decision (reason required, locked fields unchanged). */
    public DecisionDetail update(UUID id, DecisionCommand command) {
        InvestmentDecision decision = load(id, command.expectedVersion());
        if (decision.getStatus() == DecisionStatus.CANCELLED) {
            throw new InvalidTransitionException("edit", decision.getStatus());
        }
        DecisionContent current = decision.content();
        DecisionContent next = content(command, current);
        Map<String, Object> changes = diff(current.asMap(), next.asMap());
        if (changes.isEmpty()) {
            return queries.detail(id);
        }
        RevisionKind kind = RevisionKind.EDITED;
        String reason = blankToNull(command.amendmentReason());
        if (decision.getStatus() != DecisionStatus.DRAFT) {
            List<String> locked = changes.keySet().stream().filter(DecisionContent.LOCKED_AFTER_FINALIZE::contains).toList();
            if (!locked.isEmpty()) {
                throw new FieldLockedException(locked);
            }
            if (reason == null) {
                throw new InvalidDecisionException("An amendment reason is required to change a finalized decision");
            }
            kind = RevisionKind.AMENDED;
        }
        Instant now = clock.instant();
        decision.apply(next, now);
        flush(decision);
        revise(decision, kind, reason, changes, now);
        return queries.detail(id);
    }

    /** DRAFT → DECIDED. Takes the immutable portfolio and investor snapshot. */
    public DecisionDetail finalizeDecision(UUID id, TransitionCommand command) {
        InvestmentDecision decision = load(id, command.expectedVersion());
        if (decision.getStatus() != DecisionStatus.DRAFT) {
            throw new InvalidTransitionException("finalize", decision.getStatus());
        }
        if (decision.getRationale() == null) {
            throw new InvalidDecisionException("Write a rationale before finalizing the decision");
        }
        Instant now = clock.instant();
        DecisionSnapshot snapshot = snapshot(decision.content(), now);
        snapshots.saveAndFlush(new DecisionPortfolioSnapshot(id, now, snapshot.valuationDate(), json.writeValueAsString(snapshot)));
        decision.finalizeDecision(now);
        flush(decision);
        Map<String, Object> changes = statusChange(DecisionStatus.DRAFT, DecisionStatus.DECIDED);
        changes.put("snapshotTakenAt", now);
        revise(decision, RevisionKind.FINALIZED, blankToNull(command.reason()), changes, now);
        log.info("Decision finalized id={}", id);
        return queries.detail(id);
    }

    /**
     * DECIDED → EXECUTED, on the user's request only. BUY, SELL and REBALANCE need at least one linked transaction
     * that still exists; HOLD and OBSERVE need none.
     */
    public DecisionDetail execute(UUID id, TransitionCommand command) {
        InvestmentDecision decision = load(id, command.expectedVersion());
        if (decision.getStatus() != DecisionStatus.DECIDED) {
            throw new InvalidTransitionException("mark as executed", decision.getStatus());
        }
        if (decision.getDecisionType().requiresTransaction() && links.countByDecisionIdAndRemovedAtIsNullAndTransactionIdIsNotNull(id) == 0) {
            throw new InvalidTransitionException("Link at least one portfolio transaction before marking a "
                    + decision.getDecisionType() + " decision as executed");
        }
        Instant now = clock.instant();
        decision.execute(now);
        flush(decision);
        revise(decision, RevisionKind.EXECUTED, blankToNull(command.reason()), statusChange(DecisionStatus.DECIDED, DecisionStatus.EXECUTED), now);
        return queries.detail(id);
    }

    /** DRAFT or DECIDED → CANCELLED. The decision and its history stay. */
    public DecisionDetail cancel(UUID id, TransitionCommand command) {
        InvestmentDecision decision = load(id, command.expectedVersion());
        String reason = blankToNull(command.reason());
        if (reason == null) {
            throw new InvalidDecisionException("A reason is required to cancel a decision");
        }
        DecisionStatus before = decision.getStatus();
        Instant now = clock.instant();
        if (!decision.cancel(reason, now)) {
            throw new InvalidTransitionException("cancel", before);
        }
        flush(decision);
        revise(decision, RevisionKind.CANCELLED, reason, statusChange(before, DecisionStatus.CANCELLED), now);
        return queries.detail(id);
    }

    public DecisionDetail attachEvidence(UUID id, EvidenceCommand command) {
        InvestmentDecision decision = load(id, command.expectedVersion());
        requireOpen(decision, "attach evidence to");
        if (evidence.existsByDecisionIdAndKindAndSourceIdAndRemovedAtIsNull(id, command.kind(), command.sourceId())) {
            throw new DuplicateEvidenceException(command.kind(), command.sourceId());
        }
        Resolved source = resolver.resolve(command.kind(), command.sourceId());
        Instant now = clock.instant();
        DecisionEvidence saved = evidence.save(new DecisionEvidence(id, command.kind(), command.role(), command.sourceId(),
                source.contentSha256(), cap(source.title(), 1000), cap(source.publisher(), 200), source.publishedOn(),
                cap(source.location(), 500), source.excerpt(), blankToNull(command.note()), now));
        decision.touch(now);
        flush(decision);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("evidenceId", saved.getId());
        changes.put("kind", command.kind());
        changes.put("sourceId", command.sourceId());
        changes.put("role", command.role());
        changes.put("title", saved.getTitle());
        revise(decision, RevisionKind.EVIDENCE_ATTACHED, blankToNull(command.note()), changes, now);
        return queries.detail(id);
    }

    public DecisionDetail removeEvidence(UUID id, UUID evidenceId, TransitionCommand command) {
        InvestmentDecision decision = load(id, command.expectedVersion());
        requireOpen(decision, "remove evidence from");
        DecisionEvidence item = evidence.findByIdAndDecisionId(evidenceId, id)
                .filter(e -> !e.isRemoved())
                .orElseThrow(() -> new EvidenceNotFoundException(evidenceId));
        String reason = requireReason(command.reason(), "remove evidence");
        Instant now = clock.instant();
        item.remove(reason, now);
        decision.touch(now);
        flush(decision);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("evidenceId", evidenceId);
        changes.put("kind", item.getKind());
        changes.put("sourceId", item.getSourceId());
        changes.put("title", item.getTitle());
        revise(decision, RevisionKind.EVIDENCE_REMOVED, reason, changes, now);
        return queries.detail(id);
    }

    public DecisionDetail linkTransaction(UUID id, LinkCommand command) {
        InvestmentDecision decision = load(id, command.expectedVersion());
        requireOpen(decision, "link a transaction to");
        if (decision.getPortfolioId() == null) {
            throw new InvalidTransactionLinkException("Only a decision with a portfolio can be linked to transactions");
        }
        TransactionView transaction = portfolios.transactions(List.of(command.transactionId())).stream().findFirst()
                .orElseThrow(() -> new TransactionNotFoundException(command.transactionId()));
        validateLink(decision, transaction);
        if (links.existsByDecisionIdAndTransactionRefAndRemovedAtIsNull(id, transaction.id())) {
            throw new DuplicateTransactionLinkException(transaction.id());
        }
        Instant now = clock.instant();
        DecisionTransactionLink link = links.save(new DecisionTransactionLink(id, transaction.id(), transaction.portfolioId(),
                transaction.symbol(), transaction.type().name(), transaction.tradeDate(), transaction.quantity(), transaction.price(),
                blankToNull(command.note()), now));
        decision.touch(now);
        flush(decision);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("linkId", link.getId());
        changes.put("transactionId", transaction.id());
        changes.put("symbol", transaction.symbol());
        changes.put("transactionType", transaction.type());
        changes.put("tradeDate", transaction.tradeDate());
        changes.put("quantity", transaction.quantity());
        changes.put("price", transaction.price());
        revise(decision, RevisionKind.TRANSACTION_LINKED, blankToNull(command.note()), changes, now);
        return queries.detail(id);
    }

    public DecisionDetail unlinkTransaction(UUID id, UUID linkId, TransitionCommand command) {
        InvestmentDecision decision = load(id, command.expectedVersion());
        requireOpen(decision, "unlink a transaction from");
        DecisionTransactionLink link = links.findByIdAndDecisionId(linkId, id)
                .filter(l -> !l.isRemoved())
                .orElseThrow(() -> new TransactionLinkNotFoundException(linkId));
        String reason = requireReason(command.reason(), "unlink a transaction");
        Instant now = clock.instant();
        link.remove(reason, now);
        decision.touch(now);
        flush(decision);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("linkId", linkId);
        changes.put("transactionId", link.getTransactionRef());
        changes.put("symbol", link.getSymbol());
        revise(decision, RevisionKind.TRANSACTION_UNLINKED, reason, changes, now);
        return queries.detail(id);
    }

    private DecisionSnapshot snapshot(DecisionContent content, Instant now) {
        LocalDate today = portfolios.today();
        PortfolioSnapshot portfolio = null;
        Allocation allocation = null;
        String note = null;
        if (content.portfolioId() != null) {
            portfolio = portfolios.snapshot(content.portfolioId());
            allocation = portfolios.allocation(content.portfolioId());
        } else if (content.portfolioName() != null) {
            note = "The portfolio \"" + content.portfolioName() + "\" was deleted before the decision was finalized; "
                    + "no holdings were recorded.";
        }
        PriceView latest = content.instrumentId() == null ? null : portfolios.prices(content.instrumentId()).stream()
                .filter(p -> !p.asOf().isAfter(today))
                .max(Comparator.comparing(PriceView::asOf).thenComparing(PriceView::createdAt))
                .orElse(null);
        return DecisionSnapshotFactory.build(now, today, portfolio, allocation, content.instrumentId(), content.symbol(), latest,
                investor.profile(), investor.goals(), note);
    }

    private static void validateLink(InvestmentDecision decision, TransactionView transaction) {
        if (!transaction.portfolioId().equals(decision.getPortfolioId())) {
            throw new InvalidTransactionLinkException("The transaction belongs to another portfolio");
        }
        DecisionType type = decision.getDecisionType();
        if (type == DecisionType.REBALANCE) {
            return;
        }
        if (decision.getInstrumentId() == null || !decision.getInstrumentId().equals(transaction.instrumentId())) {
            throw new InvalidTransactionLinkException("The transaction is for " + transaction.symbol() + ", not for "
                    + Objects.requireNonNullElse(decision.getSymbol(), "the decision's instrument"));
        }
        boolean matches = switch (type) {
            case BUY -> transaction.type() == TransactionType.BUY || transaction.type() == TransactionType.OPENING_POSITION;
            case SELL -> transaction.type() == TransactionType.SELL;
            default -> true;
        };
        if (!matches) {
            throw new InvalidTransactionLinkException("A " + type + " decision cannot be linked to a " + transaction.type() + " transaction");
        }
    }

    /** Builds the content, keeping the stored portfolio name and symbol copies when the references are unchanged. */
    private DecisionContent content(DecisionCommand c, DecisionContent current) {
        if (c.decisionType() != DecisionType.REBALANCE && c.instrumentId() == null
                && (current == null || current.symbol() == null || current.instrumentId() != null)) {
            throw new InvalidDecisionException("An instrument is required for BUY, SELL, HOLD and OBSERVE decisions");
        }
        if (c.decisionType() == DecisionType.REBALANCE && c.portfolioId() == null
                && (current == null || current.portfolioId() != null || current.portfolioName() == null)) {
            throw new InvalidDecisionException("A rebalance decision needs a portfolio");
        }
        String portfolioName;
        if (c.portfolioId() == null) {
            // Keeps the copy of a portfolio that was deleted after the decision was recorded.
            portfolioName = current != null && current.portfolioId() == null ? current.portfolioName() : null;
        } else if (current != null && c.portfolioId().equals(current.portfolioId())) {
            portfolioName = current.portfolioName();
        } else {
            portfolioName = portfolios.portfolio(c.portfolioId()).name();
        }
        String symbol;
        if (c.instrumentId() == null) {
            symbol = current != null && current.instrumentId() == null ? current.symbol() : null;
        } else if (current != null && c.instrumentId().equals(current.instrumentId())) {
            symbol = current.symbol();
        } else {
            symbol = portfolios.instrument(c.instrumentId()).symbol();
        }
        LocalDate decisionDate = c.decisionDate() != null ? c.decisionDate()
                : current != null ? current.decisionDate() : portfolios.today();
        return new DecisionContent(c.portfolioId(), portfolioName, c.instrumentId(), symbol, c.decisionType(), decisionDate,
                c.title().strip(), blankToNull(c.rationale()), blankToNull(c.expectedOutcome()), blankToNull(c.timeHorizon()),
                blankToNull(c.mainAssumptions()), blankToNull(c.risks()), blankToNull(c.invalidationConditions()), c.reviewDate(),
                blankToNull(c.notes()), c.intendedQuantity(), c.intendedAllocationPct(), c.intendedPrice());
    }

    private InvestmentDecision load(UUID id, Integer expectedVersion) {
        InvestmentDecision decision = decisions.findById(id).orElseThrow(() -> new DecisionNotFoundException(id));
        if (expectedVersion == null) {
            throw new InvalidDecisionException("expectedVersion is required");
        }
        if (decision.getVersion() != expectedVersion) {
            throw new DecisionConflictException(decision.getVersion());
        }
        return decision;
    }

    private static void requireOpen(InvestmentDecision decision, String action) {
        if (decision.getStatus() == DecisionStatus.CANCELLED) {
            throw new InvalidTransitionException(action, decision.getStatus());
        }
    }

    private static String requireReason(String reason, String action) {
        String value = blankToNull(reason);
        if (value == null) {
            throw new InvalidDecisionException("A reason is required to " + action);
        }
        return value;
    }

    /** Writes the decision now, so a concurrent change surfaces as a conflict instead of at commit. */
    private void flush(InvestmentDecision decision) {
        try {
            decisions.saveAndFlush(decision);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new DecisionConflictException(null);
        }
    }

    private void revise(InvestmentDecision decision, RevisionKind kind, String reason, Map<String, Object> changes, Instant now) {
        int sequence = (int) revisions.countByDecisionId(decision.getId()) + 1;
        revisions.saveAndFlush(new DecisionRevision(decision.getId(), sequence, kind, reason, decision.getStatus(),
                json.writeValueAsString(changes), now));
    }

    /** Fields whose value differs, as {@code {field: {from, to}}}. Numbers compare by value (1.0 equals 1). */
    static Map<String, Object> diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            Object from = before.get(entry.getKey());
            Object to = entry.getValue();
            if (!same(from, to)) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("from", from);
                change.put("to", to);
                changes.put(entry.getKey(), change);
            }
        }
        return changes;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    private static Map<String, Object> statusChange(DecisionStatus from, DecisionStatus to) {
        Map<String, Object> change = new LinkedHashMap<>();
        change.put("from", from);
        change.put("to", to);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("status", change);
        return changes;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String cap(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
