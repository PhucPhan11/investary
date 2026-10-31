package com.investary.automation;

import java.time.Duration;
import java.util.List;

import org.awaitility.Awaitility;
import org.springframework.jdbc.core.JdbcTemplate;

import com.investary.automation.application.workflow.WorkflowJobProcessor;
import com.investary.automation.persistence.AutomationJobQueue;
import com.investary.knowledge.KnowledgeTestSupport;
import com.investary.knowledge.index.KnowledgeIndexProcessor;
import com.investary.knowledge.index.KnowledgeIndexQueue;
import com.investary.research.download.DownloadJobProcessor;
import com.investary.research.download.DownloadJobQueue;

/**
 * Runs workflows synchronously in tests (all background workers are disabled there): automation jobs,
 * the downloads they request and the indexing they wait for, round by round until nothing is due.
 */
public final class WorkflowTestSupport {

    private final AutomationJobQueue jobs;
    private final WorkflowJobProcessor processor;
    private final DownloadJobQueue downloads;
    private final DownloadJobProcessor downloadProcessor;
    private final KnowledgeIndexQueue index;
    private final KnowledgeIndexProcessor indexProcessor;
    private final JdbcTemplate jdbc;

    public WorkflowTestSupport(AutomationJobQueue jobs, WorkflowJobProcessor processor, DownloadJobQueue downloads,
                               DownloadJobProcessor downloadProcessor, KnowledgeIndexQueue index, KnowledgeIndexProcessor indexProcessor,
                               JdbcTemplate jdbc) {
        this.jobs = jobs;
        this.processor = processor;
        this.downloads = downloads;
        this.downloadProcessor = downloadProcessor;
        this.index = index;
        this.indexProcessor = indexProcessor;
        this.jdbc = jdbc;
    }

    /** Processes automation, download and indexing work until no workflow job is active. */
    public void runAll() {
        for (int round = 0; round < 200; round++) {
            List<AutomationJobQueue.ClaimedJob> claimed = jobs.claim(20);
            claimed.forEach(processor::process);
            List<DownloadJobQueue.ClaimedJob> downloading = downloads.claim(20);
            downloading.forEach(downloadProcessor::process);
            KnowledgeTestSupport.processQueued(index, indexProcessor);
            Integer active = jdbc.queryForObject(
                    "select count(*) from automation_jobs where status in ('PENDING', 'RUNNING', 'WAITING')", Integer.class);
            if (active == null || active == 0) {
                return;
            }
            if (claimed.isEmpty() && downloading.isEmpty()) {
                // Waits and retries are scheduled a millisecond or so ahead.
                Awaitility.await().pollDelay(Duration.ofMillis(5)).until(() -> true);
            }
        }
        throw new AssertionError("Workflows did not finish");
    }

    /** Runs only the automation jobs that are due now, once. */
    public int runDueJobsOnce() {
        List<AutomationJobQueue.ClaimedJob> claimed = jobs.claim(20);
        claimed.forEach(processor::process);
        return claimed.size();
    }
}
