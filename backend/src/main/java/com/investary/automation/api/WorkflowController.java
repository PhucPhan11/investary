package com.investary.automation.api;

import java.net.URI;
import java.util.UUID;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.investary.automation.application.workflow.WorkflowService;
import com.investary.automation.application.workflow.WorkflowViews.JobView;
import com.investary.automation.application.workflow.WorkflowViews.WorkflowView;
import com.investary.automation.domain.JobStatus;
import com.investary.automation.domain.JobType;
import com.investary.common.web.PageResponse;
import com.investary.automation.application.workflow.WorkflowViews.JobDetailView;
import com.investary.automation.domain.WorkflowSourceType;
import com.investary.automation.persistence.AutomationJobQueue.JobFilter;

/**
 * Research workflows and automation jobs (Phase 5 / Milestone 2). Local-only, like the rest of
 * {@code /api/v1/automation} ({@link LocalOnlyRequestFilter}).
 */
@RestController
@RequestMapping("/api/v1/automation")
public class WorkflowController {

    private static final int MAX_PAGE_SIZE = 100;
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final WorkflowService workflows;

    public WorkflowController(WorkflowService workflows) {
        this.workflows = workflows;
    }

    /**
     * @param from       first day (Asia/Ho_Chi_Minh) of the creation date range, inclusive
     * @param to         last day of the creation date range, inclusive
     * @param sourceType EMAIL or DOCUMENT
     * @param ruleId     Gmail source rule that matched the email
     */
    @GetMapping("/jobs")
    public PageResponse<JobView> jobs(@RequestParam(required = false) JobStatus status,
                                      @RequestParam(required = false) JobType type,
                                      @RequestParam(required = false) UUID workflowId,
                                      @RequestParam(required = false) LocalDate from,
                                      @RequestParam(required = false) LocalDate to,
                                      @RequestParam(required = false) WorkflowSourceType sourceType,
                                      @RequestParam(required = false) UUID ruleId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        JobFilter filter = new JobFilter(status, type, workflowId, from == null ? null : from.atStartOfDay(ZONE).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(ZONE).toInstant(), sourceType == null ? null : sourceType.name(), ruleId);
        return workflows.jobs(filter, Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/jobs/{id}")
    public JobDetailView job(@PathVariable UUID id) {
        return workflows.jobDetail(id);
    }

    @PostMapping("/jobs/{id}/retry")
    public ResponseEntity<JobView> retry(@PathVariable UUID id) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/automation/jobs/" + id)).body(workflows.retry(id));
    }

    /**
     * @param sourceId an email or library document id
     */
    @GetMapping("/workflows/{sourceId}")
    public WorkflowView workflow(@PathVariable UUID sourceId) {
        return workflows.view(sourceId);
    }

    @PostMapping("/workflows/{sourceId}/process")
    public ResponseEntity<WorkflowView> process(@PathVariable UUID sourceId) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/automation/workflows/" + sourceId))
                .body(workflows.process(sourceId));
    }
}
