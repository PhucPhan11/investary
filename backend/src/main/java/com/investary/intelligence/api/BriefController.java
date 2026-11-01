package com.investary.intelligence.api;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.investary.common.web.PageResponse;
import com.investary.intelligence.application.BriefService;
import com.investary.intelligence.application.BriefViews.BriefJobView;
import com.investary.intelligence.application.BriefViews.BriefListItem;
import com.investary.intelligence.application.BriefViews.BriefView;
import com.investary.intelligence.domain.BriefStatus;

/**
 * Daily investment briefs (Phase 5 / Milestone 3). Generation requests only enqueue a job (202); poll
 * {@code /briefs/jobs/{id}} for the result.
 */
@RestController
@RequestMapping("/api/v1/briefs")
public class BriefController {

    private static final int MAX_PAGE_SIZE = 100;

    private final BriefService briefs;

    public BriefController(BriefService briefs) {
        this.briefs = briefs;
    }

    /**
     * @param from first business date (local date of the period end), inclusive
     * @param to   last business date, inclusive
     */
    @GetMapping
    public PageResponse<BriefListItem> list(@RequestParam(required = false) UUID portfolioId,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) BriefStatus status,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size) {
        return briefs.list(portfolioId, from, to, status, Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/{id}")
    public BriefView brief(@PathVariable UUID id) {
        return briefs.view(id);
    }

    @PostMapping("/generate")
    public ResponseEntity<BriefJobView> generate(@RequestBody(required = false) GenerateRequest request) {
        GenerateRequest body = request == null ? new GenerateRequest(null, null) : request;
        BriefJobView job = briefs.generate(body.portfolioId(), body.date());
        return ResponseEntity.accepted().location(URI.create("/api/v1/briefs/jobs/" + job.id())).body(job);
    }

    @GetMapping("/jobs/{id}")
    public BriefJobView job(@PathVariable UUID id) {
        return briefs.job(id);
    }

    @PostMapping("/{id}/regenerate")
    public ResponseEntity<BriefJobView> regenerate(@PathVariable UUID id) {
        BriefJobView job = briefs.regenerate(id);
        return ResponseEntity.accepted().location(URI.create("/api/v1/briefs/jobs/" + job.id())).body(job);
    }

    /**
     * @param portfolioId omit for a general brief
     * @param date        business date (local date of the period end in the brief zone); default today
     */
    public record GenerateRequest(UUID portfolioId, LocalDate date) {
    }
}
