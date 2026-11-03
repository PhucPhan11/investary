package com.investary.intelligence.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.investary.intelligence.application.ImpactAssessmentService;
import com.investary.intelligence.application.IntelligenceViews.AssessmentListItem;
import com.investary.intelligence.application.IntelligenceViews.AssessmentView;
import com.investary.intelligence.application.IntelligenceViews.PortfolioAnalysis;
import com.investary.intelligence.application.IntelligenceViews.RelevantResearch;
import com.investary.intelligence.application.PortfolioAnalysisService;
import com.investary.intelligence.application.PortfolioAssistantService;
import com.investary.intelligence.application.PortfolioAssistantService.PortfolioAnswer;
import com.investary.intelligence.application.ResearchMatcher.ResearchFilters;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Portfolio-aware research intelligence. Read-only with respect to portfolio data: these endpoints never
 * change holdings, transactions or the investor profile. Single-user, local use only.
 */
@RestController
@RequestMapping("/api/v1/intelligence")
public class IntelligenceController {

    private final PortfolioAnalysisService analysis;
    private final ImpactAssessmentService assessments;
    private final PortfolioAssistantService assistant;

    public IntelligenceController(PortfolioAnalysisService analysis, ImpactAssessmentService assessments,
                                  PortfolioAssistantService assistant) {
        this.analysis = analysis;
        this.assessments = assessments;
        this.assistant = assistant;
    }

    /** Deterministic analysis: valuation status, concentration, profile checks and research coverage (no AI). */
    @PostMapping("/portfolios/{id}/analyze")
    public PortfolioAnalysis analyze(@PathVariable UUID id) {
        return analysis.analyze(id);
    }

    @PostMapping("/portfolios/{id}/relevant-research")
    public RelevantResearch relevantResearch(@PathVariable UUID id, @Valid @RequestBody(required = false) FilterRequest request) {
        return analysis.relevantResearch(id, filters(request));
    }

    @PostMapping("/portfolios/{id}/impact-assessments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssessmentView createAssessment(@PathVariable UUID id, @Valid @RequestBody(required = false) FilterRequest request) {
        return assessments.create(id, filters(request));
    }

    @GetMapping("/portfolios/{id}/impact-assessments")
    public List<AssessmentListItem> assessments(@PathVariable UUID id) {
        return assessments.list(id);
    }

    @GetMapping("/impact-assessments/{assessmentId}")
    public AssessmentView assessment(@PathVariable UUID assessmentId) {
        return assessments.view(assessmentId);
    }

    @DeleteMapping("/impact-assessments/{assessmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAssessment(@PathVariable UUID assessmentId) {
        assessments.delete(assessmentId);
    }

    /**
     * Portfolio-aware question. {@code portfolioId} is required ({@code 422 PORTFOLIO_REQUIRED} otherwise);
     * the backend never chooses a portfolio on the user's behalf.
     */
    @PostMapping("/ask")
    public PortfolioAnswer ask(@Valid @RequestBody AskRequest request) {
        return assistant.ask(request.question().strip(), request.portfolioId(), request.maxSources());
    }

    private static ResearchFilters filters(FilterRequest request) {
        return request == null ? ResearchFilters.NONE : new ResearchFilters(
                request.brokerage() == null || request.brokerage().isBlank() ? null : request.brokerage().strip(), request.from(), request.to());
    }

    public record FilterRequest(@Size(max = 100) String brokerage, LocalDate from, LocalDate to) {

        @AssertTrue(message = "from must not be after to")
        public boolean isDateRangeValid() {
            return from == null || to == null || !from.isAfter(to);
        }
    }

    public record AskRequest(
            @NotBlank @Size(min = 3, max = 1000) String question,
            UUID portfolioId,
            @Min(1) @Max(20) Integer maxSources) {
    }
}
