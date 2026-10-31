package com.investary.automation.api;

import java.net.URI;
import java.util.UUID;

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

/**
 * Research workflows and automation jobs (Phase 5 / Milestone 2). Local-only, like the rest of
 * {@code /api/v1/automation} ({@link LocalOnlyRequestFilter}).
 */
@RestController
@RequestMapping("/api/v1/automation")
public class WorkflowController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WorkflowService workflows;

    public WorkflowController(WorkflowService workflows) {
        this.workflows = workflows;
    }

    @GetMapping("/jobs")
    public PageResponse<JobView> jobs(@RequestParam(required = false) JobStatus status,
                                      @RequestParam(required = false) JobType type,
                                      @RequestParam(required = false) UUID workflowId,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return workflows.jobs(status, type, workflowId, Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/jobs/{id}")
    public JobView job(@PathVariable UUID id) {
        return workflows.job(id);
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
