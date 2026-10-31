package com.investary.automation.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.investary.automation.GmailProperties;
import com.investary.automation.application.GmailAccessTokens.ReconnectRequiredException;
import com.investary.automation.domain.EmailSourceRule;
import com.investary.automation.domain.MessageOutcome;
import com.investary.automation.domain.SyncMode;
import com.investary.automation.domain.SyncRunStatus;
import com.investary.automation.google.GmailApiClient;
import com.investary.automation.google.GmailApiClient.HistoryPage;
import com.investary.automation.google.GmailApiClient.HistoryRecord;
import com.investary.automation.google.GmailApiClient.MessageMetadata;
import com.investary.automation.google.GmailApiClient.MessagePage;
import com.investary.automation.google.GmailApiClient.MessageRef;
import com.investary.automation.google.GmailApiClient.TokenSource;
import com.investary.automation.google.GoogleExceptions.GoogleApiException;
import com.investary.automation.google.GoogleExceptions.GoogleTransientException;
import com.investary.automation.google.GoogleExceptions.HistoryExpiredException;
import com.investary.automation.google.GoogleExceptions.MessageNotFoundException;
import com.investary.automation.persistence.EmailSourceRuleRepository;
import com.investary.automation.persistence.GmailAccountStore;
import com.investary.automation.persistence.GmailAccountStore.AccountRow;
import com.investary.automation.persistence.GmailMessageStore;
import com.investary.automation.persistence.GmailSyncRunStore;
import com.investary.automation.persistence.GmailSyncRunStore.Counters;
import com.investary.email.EmailProperties;
import com.investary.email.application.EmailExceptions.DuplicateEmailException;
import com.investary.email.application.EmailExceptions.EmailTooLargeException;
import com.investary.email.application.EmailExceptions.InvalidEmailException;
import com.investary.email.application.EmailImportService;
import com.investary.email.domain.SourceType;

/**
 * Runs one sync of one account while holding its lock.
 * <ul>
 *   <li><b>INITIAL</b> (no cursor yet): remember the mailbox history id, list each enabled rule's messages of the
 *       lookback window, then make the remembered history id the cursor.</li>
 *   <li><b>INCREMENTAL</b>: {@code history.list} from the cursor; the cursor advances after each page to the last
 *       history record whose messages are all handled, and to the mailbox history id at the end.</li>
 *   <li><b>RECOVERY</b> (cursor expired, 404): like INITIAL but over a bounded window since the last success.</li>
 * </ul>
 * Each new message: metadata → verified sender → first matching rule → raw bytes → Phase 2 import, then a
 * {@code gmail_messages} row. A failure that outlasts the retries ends the run without moving the cursor, so the
 * next run sees the same messages again; handled ones are skipped by their Gmail id (or caught by the checksum).
 */
@Component
public class GmailSyncRunner {

    private static final Logger log = LoggerFactory.getLogger(GmailSyncRunner.class);
    private static final Set<String> IGNORED_LABELS = Set.of("SPAM", "TRASH", "DRAFT", "CHAT");

    private final GmailAccountStore accounts;
    private final GmailSyncRunStore runs;
    private final GmailMessageStore messages;
    private final EmailSourceRuleRepository rules;
    private final GmailApiClient gmail;
    private final GmailAccessTokens tokens;
    private final EmailImportService emails;
    private final GmailProperties properties;
    private final EmailProperties emailProperties;
    private final Clock clock;

    public GmailSyncRunner(GmailAccountStore accounts, GmailSyncRunStore runs, GmailMessageStore messages,
                           EmailSourceRuleRepository rules, GmailApiClient gmail, GmailAccessTokens tokens,
                           EmailImportService emails, GmailProperties properties, EmailProperties emailProperties, Clock clock) {
        this.accounts = accounts;
        this.runs = runs;
        this.messages = messages;
        this.rules = rules;
        this.gmail = gmail;
        this.tokens = tokens;
        this.emails = emails;
        this.properties = properties;
        this.emailProperties = emailProperties;
        this.clock = clock;
    }

    /** Runs the sync and always releases the lock and closes the run. */
    public void run(UUID accountId, UUID runId, UUID lockToken) {
        Run run = new Run(accountId, runId, lockToken);
        try {
            AccountRow account = accounts.find(accountId).orElseThrow(() -> new LockLostException());
            run.tokens = tokens.source(accountId);
            run.rules = rules.findEnabled();
            if (run.rules.isEmpty()) {
                run.warning = "No enabled source rules; nothing was imported";
            }
            boolean complete;
            if (account.historyId() == null || account.initialSyncCompletedAt() == null) {
                run.mode = SyncMode.INITIAL;
                complete = initial(run, account);
            } else {
                try {
                    run.mode = SyncMode.INCREMENTAL;
                    run.startHistoryId = account.historyId();
                    complete = incremental(run, account.historyId());
                } catch (HistoryExpiredException e) {
                    log.warn("Gmail history cursor expired, running bounded recovery accountId={}", accountId);
                    run.mode = SyncMode.RECOVERY;
                    complete = recovery(run, account);
                }
            }
            SyncRunStatus status = complete ? SyncRunStatus.SUCCEEDED : SyncRunStatus.PARTIAL;
            if (!complete) {
                run.warning = join(run.warning, "Stopped after " + properties.sync().maxMessagesPerRun()
                        + " matching messages; the next sync continues");
            }
            if (complete) {
                accounts.recordSuccess(accountId, lockToken, clock.instant());
            }
            finish(run, status, null, null);
            log.info("Gmail sync finished accountId={} runId={} mode={} status={} listed={} imported={} duplicates={} skipped={} rejected={}",
                    accountId, runId, run.mode, status, run.counters.listed, run.counters.imported, run.counters.duplicates,
                    run.counters.skipped, run.counters.rejected);
        } catch (ReconnectRequiredException e) {
            fail(run, e.code(), e.getMessage());
        } catch (GoogleTransientException e) {
            fail(run, e.code(), e.getMessage());
        } catch (GoogleApiException e) {
            fail(run, e.code(), e.getMessage());
        } catch (LockLostException e) {
            fail(run, "LOCK_LOST", e.getMessage());
        } catch (RuntimeException e) {
            log.error("Gmail sync failed unexpectedly accountId={} runId={}", accountId, runId, e);
            fail(run, "INTERNAL_ERROR", "The sync failed unexpectedly; see the server log");
        } finally {
            accounts.releaseLock(accountId, lockToken);
        }
    }

    private boolean initial(Run run, AccountRow account) {
        String pending = account.pendingHistoryId();
        if (pending == null) {
            pending = gmail.profile(run.tokens).historyId();
            guard(accounts.setPendingHistoryId(run.accountId, run.lockToken, pending));
        }
        run.endHistoryId = pending;
        Instant after = clock.instant().minus(Duration.ofDays(properties.sync().initialLookbackDays()));
        return listAndComplete(run, after);
    }

    private boolean recovery(Run run, AccountRow account) {
        String current = gmail.profile(run.tokens).historyId();
        guard(accounts.setPendingHistoryId(run.accountId, run.lockToken, current));
        run.endHistoryId = current;
        Instant now = clock.instant();
        Instant floor = now.minus(Duration.ofDays(properties.sync().recoveryMaxLookbackDays()));
        Instant after = account.lastSyncSuccessAt() == null ? floor : account.lastSyncSuccessAt().minus(Duration.ofDays(1));
        if (after.isBefore(floor)) {
            after = floor;
            run.warning = join(run.warning, "The Gmail history expired; mail older than "
                    + properties.sync().recoveryMaxLookbackDays() + " days was not re-checked");
        } else {
            run.warning = join(run.warning, "The Gmail history expired; re-checked mail since the last successful sync");
        }
        return listAndComplete(run, after);
    }

    private boolean listAndComplete(Run run, Instant after) {
        if (run.rules.isEmpty()) {
            return true; // nothing to list; the initial sync stays pending until a rule is enabled
        }
        Set<String> seen = new HashSet<>();
        for (EmailSourceRule rule : run.rules) {
            String query = RuleMatcher.gmailQuery(rule, after);
            String pageToken = null;
            do {
                MessagePage page = gmail.listMessages(run.tokens, query, pageToken);
                for (MessageRef ref : page.messages()) {
                    if (seen.add(ref.id())) {
                        run.counters.listed++;
                        if (!handle(run, ref.id())) {
                            return false;
                        }
                    }
                }
                renew(run);
                pageToken = page.nextPageToken();
            } while (pageToken != null);
        }
        guard(accounts.completeListing(run.accountId, run.lockToken, clock.instant()));
        return true;
    }

    private boolean incremental(Run run, String startHistoryId) {
        Set<String> seen = new HashSet<>();
        String pageToken = null;
        String lastHandled = startHistoryId;
        HistoryPage page;
        do {
            page = gmail.listHistory(run.tokens, startHistoryId, pageToken);
            for (HistoryRecord record : page.history()) {
                for (MessageRef ref : record.messagesAdded()) {
                    if (seen.add(ref.id())) {
                        run.counters.listed++;
                        if (!handle(run, ref.id())) {
                            checkpoint(run, lastHandled);
                            return false;
                        }
                    }
                }
                if (record.id() != null && record.id().matches("\\d{1,20}")) {
                    lastHandled = record.id();
                }
            }
            checkpoint(run, lastHandled);
            pageToken = page.nextPageToken();
        } while (pageToken != null);
        if (page.historyId() != null) {
            checkpoint(run, page.historyId());
        }
        return true;
    }

    /**
     * Handles one message and records the outcome.
     *
     * @return false if the per-run budget of matching messages is used up (the message was not handled)
     */
    private boolean handle(Run run, String messageId) {
        if (messages.exists(run.accountId, messageId)) {
            return true;
        }
        MessageMetadata metadata;
        try {
            metadata = gmail.metadata(run.tokens, messageId);
        } catch (MessageNotFoundException e) {
            run.counters.skipped++; // deleted since it was listed
            return true;
        }
        if (IGNORED_LABELS.stream().anyMatch(metadata::hasLabel)) {
            run.counters.skipped++;
            return true;
        }
        Optional<String> sender = SenderVerifier.senderAddress(metadata.headers("From"));
        String subject = metadata.headers("Subject").stream().findFirst().orElse("");
        Optional<EmailSourceRule> rule = sender.flatMap(address -> RuleMatcher.match(run.rules, address, subject));
        if (rule.isEmpty()) {
            run.counters.skipped++; // not from an approved source: not recorded
            renew(run);
            return true;
        }
        if (run.matched >= properties.sync().maxMessagesPerRun()) {
            return false; // budget used up: not recorded, so the next run handles it
        }
        run.matched++;
        UUID ruleId = rule.get().getId();
        if (SenderVerifier.failedDmarc(metadata.headers("Authentication-Results"))) {
            record(run, metadata, MessageOutcome.REJECTED, ruleId, null, "SENDER_NOT_AUTHENTICATED");
            run.counters.rejected++;
        } else if (!rule.get().isAutoImport()) {
            record(run, metadata, MessageOutcome.NOT_IMPORTED, ruleId, null, null);
            run.counters.notImported++;
        } else if (metadata.sizeEstimate() > emailProperties.maxSize().toBytes()) {
            record(run, metadata, MessageOutcome.REJECTED, ruleId, null, "EMAIL_TOO_LARGE");
            run.counters.rejected++;
        } else {
            importMessage(run, metadata, ruleId);
        }
        renew(run);
        return true;
    }

    private void importMessage(Run run, MessageMetadata metadata, UUID ruleId) {
        byte[] raw;
        try {
            raw = gmail.raw(run.tokens, metadata.id());
        } catch (MessageNotFoundException e) {
            run.counters.skipped++;
            return;
        }
        try {
            UUID emailId = emails.importRaw(raw, SourceType.GMAIL, "gmail-" + metadata.id() + ".eml");
            record(run, metadata, MessageOutcome.IMPORTED, ruleId, emailId, null);
            run.counters.imported++;
        } catch (DuplicateEmailException e) {
            record(run, metadata, MessageOutcome.DUPLICATE, ruleId, e.getExistingEmailId(), null);
            run.counters.duplicates++;
        } catch (EmailTooLargeException e) {
            record(run, metadata, MessageOutcome.REJECTED, ruleId, null, "EMAIL_TOO_LARGE");
            run.counters.rejected++;
        } catch (InvalidEmailException e) {
            record(run, metadata, MessageOutcome.REJECTED, ruleId, null, "INVALID_EMAIL");
            run.counters.rejected++;
        }
    }

    private void record(Run run, MessageMetadata metadata, MessageOutcome outcome, UUID ruleId, UUID emailId, String errorCode) {
        messages.record(run.accountId, metadata.id(), metadata.threadId(), metadata.historyId(), metadata.internalDate(),
                outcome, ruleId, emailId, run.runId, errorCode, clock.instant());
    }

    private void checkpoint(Run run, String historyId) {
        guard(accounts.checkpoint(run.accountId, run.lockToken, historyId));
        run.endHistoryId = historyId;
    }

    private void renew(Run run) {
        guard(accounts.renewLock(run.accountId, run.lockToken, properties.sync().lockLease(), clock.instant()));
    }

    private static void guard(boolean stillLocked) {
        if (!stillLocked) {
            throw new LockLostException();
        }
    }

    private void fail(Run run, String code, String message) {
        log.warn("Gmail sync failed accountId={} runId={} code={}", run.accountId, run.runId, code);
        accounts.recordError(run.accountId, run.lockToken, code, message, clock.instant());
        finish(run, SyncRunStatus.FAILED, code, message);
    }

    private void finish(Run run, SyncRunStatus status, String errorCode, String errorMessage) {
        runs.finish(run.runId, run.mode, status, run.counters, run.startHistoryId, run.endHistoryId, errorCode, errorMessage,
                run.warning, clock.instant());
    }

    private static String join(String first, String second) {
        return first == null ? second : first + ". " + second;
    }

    /** State of one run. */
    private static final class Run {
        final UUID accountId;
        final UUID runId;
        final UUID lockToken;
        final Counters counters = new Counters();
        TokenSource tokens;
        List<EmailSourceRule> rules = List.of();
        SyncMode mode;
        String startHistoryId;
        String endHistoryId;
        String warning;
        int matched;

        Run(UUID accountId, UUID runId, UUID lockToken) {
            this.accountId = accountId;
            this.runId = runId;
            this.lockToken = lockToken;
        }
    }

    /** Another sync took over the account (this one's lease expired) or the account was disconnected. */
    static class LockLostException extends RuntimeException {
        LockLostException() {
            super("The sync lost its account lock (lease expired or account disconnected)");
        }
    }
}
