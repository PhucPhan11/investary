package com.investary.knowledge.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.assistant.AssistantAnswer;
import com.investary.knowledge.assistant.ResearchAssistantService;
import com.investary.knowledge.search.HybridSearchService;
import com.investary.knowledge.search.SearchFilters;
import com.investary.knowledge.search.SearchHit;
import com.investary.knowledge.search.SearchResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Research retrieval and the AI research assistant. Single-user, local use only: these endpoints have
 * no authentication and must not be exposed publicly.
 */
@RestController
@RequestMapping("/api/v1/research")
public class ResearchController {

    private final HybridSearchService search;
    private final ResearchAssistantService assistant;

    public ResearchController(HybridSearchService search, ResearchAssistantService assistant) {
        this.search = search;
        this.assistant = assistant;
    }

    @PostMapping("/search")
    public SearchResponse search(@Valid @RequestBody SearchRequest request) {
        SearchResult result = search.search(request.query().strip(), request.filters(), request.maxResults());
        return new SearchResponse(result.mode(), result.analysis().tickers(), result.analysis().brokerages(),
                result.coverageGaps(), result.hits().stream().map(SearchHitView::of).toList());
    }

    @PostMapping("/ask")
    public AssistantAnswer ask(@Valid @RequestBody AskRequest request) {
        return assistant.ask(request.question().strip(), request.filters(), request.maxResults());
    }

    public record SearchRequest(
            @NotBlank @Size(max = 1000) String query,
            @Pattern(regexp = "^[A-Za-z0-9]{2,10}$", message = "must be a stock ticker") String ticker,
            @Size(max = 100) String brokerage,
            KnowledgeSourceType sourceType,
            LocalDate from,
            LocalDate to,
            @Min(1) @Max(20) Integer maxResults) {

        SearchFilters filters() {
            return new SearchFilters(ticker, brokerage, sourceType, from, to);
        }

        @AssertTrue(message = "from must not be after to")
        public boolean isDateRangeValid() {
            return from == null || to == null || !from.isAfter(to);
        }
    }

    public record AskRequest(
            @NotBlank @Size(min = 3, max = 1000) String question,
            @Pattern(regexp = "^[A-Za-z0-9]{2,10}$", message = "must be a stock ticker") String ticker,
            @Size(max = 100) String brokerage,
            KnowledgeSourceType sourceType,
            LocalDate from,
            LocalDate to,
            @Min(1) @Max(20) Integer maxResults) {

        SearchFilters filters() {
            return new SearchFilters(ticker, brokerage, sourceType, from, to);
        }

        @AssertTrue(message = "from must not be after to")
        public boolean isDateRangeValid() {
            return from == null || to == null || !from.isAfter(to);
        }
    }

    public record SearchResponse(SearchResult.Mode mode, List<String> detectedTickers, List<String> detectedBrokerages,
                                 List<String> coverageGaps, List<SearchHitView> results) {
    }

    public record SearchHitView(UUID chunkId, KnowledgeSourceType sourceType, UUID sourceId, String title, String brokerage,
                                LocalDate publishedOn, String dateBasis, Integer pageStart, Integer pageEnd, String section,
                                String content, List<String> tickers, Double similarity, double score,
                                Set<SearchHit.MatchType> matchedBy) {

        static SearchHitView of(SearchHit hit) {
            return new SearchHitView(hit.chunkId(), hit.sourceType(), hit.sourceId(), hit.title(), hit.brokerage(),
                    hit.publishedOn(), hit.dateBasis(), hit.pageStart(), hit.pageEnd(), hit.section(), hit.content(),
                    hit.tickers(), hit.similarity(), hit.score(), hit.matchedBy());
        }
    }
}
