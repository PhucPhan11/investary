package com.investary.journal.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.investary.common.web.PageResponse;
import com.investary.journal.application.DecisionCommands.DecisionCommand;
import com.investary.journal.application.DecisionCommands.EvidenceCommand;
import com.investary.journal.application.DecisionCommands.LinkCommand;
import com.investary.journal.application.DecisionCommands.TransitionCommand;
import com.investary.journal.application.DecisionQueryService;
import com.investary.journal.application.DecisionQueryService.DecisionFilter;
import com.investary.journal.application.DecisionService;
import com.investary.journal.application.DecisionViews.DecisionDetail;
import com.investary.journal.application.DecisionViews.DecisionSummary;
import com.investary.journal.application.DecisionViews.RevisionView;
import com.investary.journal.domain.DecisionStatus;
import com.investary.journal.domain.DecisionType;
import com.investary.journal.domain.EvidenceKind;
import com.investary.journal.domain.EvidenceRole;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * The investment decision journal. Records the user's decisions and reasoning; it never creates or changes
 * portfolio transactions.
 */
@RestController
@RequestMapping("/api/v1/decisions")
public class DecisionController {

    private final DecisionQueryService queries;
    private final DecisionService decisions;

    public DecisionController(DecisionQueryService queries, DecisionService decisions) {
        this.queries = queries;
        this.decisions = decisions;
    }

    @GetMapping
    public PageResponse<DecisionSummary> list(@RequestParam(required = false) UUID portfolioId,
                                              @RequestParam(required = false) String ticker,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(required = false) DecisionStatus status,
                                              @RequestParam(required = false) DecisionType type,
                                              @PageableDefault(size = 20, sort = {"decisionDate", "createdAt"}, direction = Sort.Direction.DESC)
                                              Pageable pageable) {
        return PageResponse.from(queries.list(new DecisionFilter(portfolioId, ticker, from, to, status, type), pageable),
                Function.identity());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DecisionDetail create(@Valid @RequestBody DecisionRequest request) {
        return decisions.create(request.command());
    }

    @GetMapping("/{id}")
    public DecisionDetail get(@PathVariable UUID id) {
        return queries.detail(id);
    }

    @PutMapping("/{id}")
    public DecisionDetail update(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return decisions.update(id, request.command());
    }

    @PostMapping("/{id}/finalize")
    public DecisionDetail finalizeDecision(@PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return decisions.finalizeDecision(id, request.command());
    }

    @PostMapping("/{id}/execute")
    public DecisionDetail execute(@PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return decisions.execute(id, request.command());
    }

    @PostMapping("/{id}/cancel")
    public DecisionDetail cancel(@PathVariable UUID id, @Valid @RequestBody TransitionRequest request) {
        return decisions.cancel(id, request.command());
    }

    @GetMapping("/{id}/history")
    public List<RevisionView> history(@PathVariable UUID id) {
        return queries.history(id);
    }

    @PostMapping("/{id}/evidence")
    @ResponseStatus(HttpStatus.CREATED)
    public DecisionDetail attachEvidence(@PathVariable UUID id, @Valid @RequestBody EvidenceRequest request) {
        return decisions.attachEvidence(id, request.command());
    }

    @DeleteMapping("/{id}/evidence/{evidenceId}")
    public DecisionDetail removeEvidence(@PathVariable UUID id, @PathVariable UUID evidenceId,
                                         @RequestParam int expectedVersion, @RequestParam String reason) {
        return decisions.removeEvidence(id, evidenceId, new TransitionCommand(expectedVersion, reason));
    }

    @PostMapping("/{id}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public DecisionDetail linkTransaction(@PathVariable UUID id, @Valid @RequestBody LinkRequest request) {
        return decisions.linkTransaction(id, request.command());
    }

    @DeleteMapping("/{id}/transactions/{linkId}")
    public DecisionDetail unlinkTransaction(@PathVariable UUID id, @PathVariable UUID linkId,
                                            @RequestParam int expectedVersion, @RequestParam String reason) {
        return decisions.unlinkTransaction(id, linkId, new TransitionCommand(expectedVersion, reason));
    }

    /**
     * @param expectedVersion required on update: the version the user edited
     * @param amendmentReason required when changing a finalized decision
     */
    public record DecisionRequest(
            UUID portfolioId,
            UUID instrumentId,
            @NotNull DecisionType decisionType,
            LocalDate decisionDate,
            @NotBlank @Size(max = 300) String title,
            @Size(max = 20000) String rationale,
            @Size(max = 20000) String expectedOutcome,
            @Size(max = 200) String timeHorizon,
            @Size(max = 20000) String mainAssumptions,
            @Size(max = 20000) String risks,
            @Size(max = 20000) String invalidationConditions,
            LocalDate reviewDate,
            @Size(max = 20000) String notes,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 18, fraction = 6) BigDecimal intendedQuantity,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 4) BigDecimal intendedAllocationPct,
            @DecimalMin("0") @Digits(integer = 18, fraction = 6) BigDecimal intendedPrice,
            @PositiveOrZero Integer expectedVersion,
            @Size(max = 2000) String amendmentReason) {

        DecisionCommand command() {
            return new DecisionCommand(portfolioId, instrumentId, decisionType, decisionDate, title, rationale, expectedOutcome,
                    timeHorizon, mainAssumptions, risks, invalidationConditions, reviewDate, notes, intendedQuantity,
                    intendedAllocationPct, intendedPrice, expectedVersion, amendmentReason);
        }
    }

    public record TransitionRequest(@NotNull @PositiveOrZero Integer expectedVersion, @Size(max = 2000) String reason) {

        TransitionCommand command() {
            return new TransitionCommand(expectedVersion, reason);
        }
    }

    public record EvidenceRequest(@NotNull EvidenceKind kind, @NotNull UUID sourceId, @NotNull EvidenceRole role,
                                  @Size(max = 2000) String note, @NotNull @PositiveOrZero Integer expectedVersion) {

        EvidenceCommand command() {
            return new EvidenceCommand(kind, sourceId, role, note, expectedVersion);
        }
    }

    public record LinkRequest(@NotNull UUID transactionId, @Size(max = 2000) String note,
                              @NotNull @PositiveOrZero Integer expectedVersion) {

        LinkCommand command() {
            return new LinkCommand(transactionId, note, expectedVersion);
        }
    }
}
