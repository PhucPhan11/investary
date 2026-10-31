package com.investary.automation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import com.investary.automation.GmailProperties;
import com.investary.automation.application.AutomationExceptions.GmailNotConnectedException;
import com.investary.automation.application.AutomationExceptions.SyncInProgressException;
import com.investary.automation.application.AutomationExceptions.SyncRunNotFoundException;
import com.investary.automation.domain.GmailAccountStatus;
import com.investary.automation.domain.SyncRunStatus;
import com.investary.automation.domain.SyncTrigger;
import com.investary.automation.persistence.GmailAccountStore;
import com.investary.automation.persistence.GmailAccountStore.AccountRow;
import com.investary.automation.persistence.GmailMessageStore;
import com.investary.automation.persistence.GmailSyncRunStore;
import com.investary.automation.persistence.GmailSyncRunStore.RunRow;
import com.investary.common.web.PageResponse;

/**
 * Starts syncs (manual or scheduled) and reports status. A sync first takes the account's database lock,
 * so a manual and a scheduled sync can never run at the same time; the work itself runs on a single
 * background thread.
 */
@Service
public class GmailSyncService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GmailSyncService.class);

    private final GmailAccountStore accounts;
    private final GmailSyncRunStore runs;
    private final GmailMessageStore messages;
    private final GmailSyncRunner runner;
    private final GmailConnectionService connection;
    private final GmailProperties properties;
    private final Clock clock;
    private final ExecutorService executor;

    public GmailSyncService(GmailAccountStore accounts, GmailSyncRunStore runs, GmailMessageStore messages, GmailSyncRunner runner,
                            GmailConnectionService connection, GmailProperties properties, Clock clock) {
        this.accounts = accounts;
        this.runs = runs;
        this.messages = messages;
        this.runner = runner;
        this.connection = connection;
        this.properties = properties;
        this.clock = clock;
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(4), runnable -> {
            Thread thread = new Thread(runnable, "gmail-sync");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Starts a sync of the connected account in the background.
     *
     * @return the new run id
     * @throws GmailNotConnectedException if no account is connected (or it needs reconnecting)
     * @throws SyncInProgressException    if a sync of the account is already running
     */
    public UUID requestSync(SyncTrigger trigger) {
        AccountRow account = accounts.findActive()
                .orElseThrow(() -> new GmailNotConnectedException("Connect a Gmail account first"));
        if (account.status() != GmailAccountStatus.CONNECTED) {
            throw new GmailNotConnectedException("The Gmail authorization expired or was revoked; reconnect Gmail");
        }
        return start(account.id(), trigger);
    }

    /** Scheduler entry point: syncs every connected account whose lock is free; never throws. */
    public void runScheduled() {
        if (!connection.configured()) {
            return;
        }
        for (UUID accountId : accounts.connectedIds()) {
            try {
                start(accountId, SyncTrigger.SCHEDULED);
            } catch (SyncInProgressException e) {
                log.debug("Scheduled Gmail sync skipped, one is running accountId={}", accountId);
            } catch (RuntimeException e) {
                log.error("Scheduled Gmail sync could not start accountId={}", accountId, e);
            }
        }
    }

    private UUID start(UUID accountId, SyncTrigger trigger) {
        Instant now = clock.instant();
        UUID lockToken = UUID.randomUUID();
        if (!accounts.tryLock(accountId, lockToken, properties.sync().lockLease(), now)) {
            throw new SyncInProgressException();
        }
        UUID runId;
        try {
            runId = runs.create(accountId, trigger, now);
            int interrupted = runs.closeInterrupted(accountId, runId, now);
            if (interrupted > 0) {
                log.warn("Closed {} interrupted Gmail sync run(s) accountId={}", interrupted, accountId);
            }
        } catch (RuntimeException e) {
            accounts.releaseLock(accountId, lockToken);
            throw e;
        }
        try {
            executor.execute(() -> runner.run(accountId, runId, lockToken));
        } catch (RejectedExecutionException e) {
            runs.finish(runId, null, SyncRunStatus.FAILED, new GmailSyncRunStore.Counters(), null, null, "BUSY",
                    "The sync queue is full; try again later", null, clock.instant());
            accounts.releaseLock(accountId, lockToken);
            throw new SyncInProgressException();
        }
        log.info("Gmail sync started accountId={} runId={} trigger={}", accountId, runId, trigger);
        return runId;
    }

    public GmailStatusView status() {
        Instant now = clock.instant();
        Optional<AccountRow> account = accounts.findActive();
        AccountView accountView = account.map(a -> new AccountView(a.id(), a.emailAddress(), a.status(),
                a.grantedScopes() == null ? List.of() : List.of(a.grantedScopes().split(" ")),
                a.connectedAt(), a.initialSyncCompletedAt() != null, a.lastSyncStartedAt(), a.lastSyncSuccessAt(),
                a.lastSyncErrorCode(), a.lastSyncError())).orElse(null);
        SyncRunView lastRun = account.flatMap(a -> runs.latest(a.id())).map(SyncRunView::of).orElse(null);
        return new GmailStatusView(connection.configured(), accountView,
                account.map(a -> a.syncRunning(now)).orElse(false), lastRun,
                account.map(a -> messages.countImported(a.id())).orElse(0L),
                properties.sync().schedulerEnabled(), properties.sync().interval().toMinutes(),
                properties.sync().initialLookbackDays());
    }

    public PageResponse<SyncRunView> history(int page, int size) {
        int safeSize = Math.clamp(size, 1, 100);
        int safePage = Math.max(page, 0);
        long total = runs.count();
        List<SyncRunView> content = runs.page(safePage, safeSize).stream().map(SyncRunView::of).toList();
        return new PageResponse<>(content, safePage, safeSize, total, (int) ((total + safeSize - 1) / safeSize));
    }

    public SyncRunView run(UUID runId) {
        return runs.find(runId).map(SyncRunView::of).orElseThrow(() -> new SyncRunNotFoundException(runId));
    }

    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
            // An unfinished run keeps its lock until the lease expires and is then closed as interrupted.
            executor.shutdownNow();
        }
    }

    /**
     * @param importedTotal messages of this account imported by Gmail sync so far
     */
    public record GmailStatusView(boolean configured, AccountView account, boolean running, SyncRunView lastRun,
                                  long importedTotal, boolean schedulerEnabled, long syncIntervalMinutes,
                                  int initialLookbackDays) {
    }

    public record AccountView(UUID id, String emailAddress, GmailAccountStatus status, List<String> grantedScopes,
                              Instant connectedAt, boolean initialSyncCompleted, Instant lastSyncStartedAt,
                              Instant lastSyncSuccessAt, String lastErrorCode, String lastError) {
    }

    public record SyncRunView(UUID id, String trigger, String mode, String status, Instant startedAt, Instant finishedAt,
                              int listed, int imported, int duplicates, int notImported, int skipped, int rejected,
                              String errorCode, String errorMessage, String warning) {

        static SyncRunView of(RunRow row) {
            return new SyncRunView(row.id(), row.trigger().name(), row.mode() == null ? null : row.mode().name(),
                    row.status().name(), row.startedAt(), row.finishedAt(), row.counters().listed, row.counters().imported,
                    row.counters().duplicates, row.counters().notImported, row.counters().skipped, row.counters().rejected,
                    row.errorCode(), row.errorMessage(), row.warning());
        }
    }
}
