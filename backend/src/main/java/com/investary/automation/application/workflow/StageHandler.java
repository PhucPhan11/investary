package com.investary.automation.application.workflow;

import com.investary.automation.domain.JobType;
import com.investary.automation.persistence.AutomationJobQueue.ClaimedJob;
import com.investary.automation.persistence.WorkflowStore.WorkflowRow;

/**
 * Runs one workflow stage by calling the existing module services. Implementations must be idempotent: a
 * job can run again after a restart, a retry or a re-opened workflow. No database transaction is open
 * while a handler runs, so HTTP and model calls never hold one.
 */
public interface StageHandler {

    JobType type();

    StageResult run(ClaimedJob job, WorkflowRow workflow);
}
