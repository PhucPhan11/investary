package com.investary.research.download;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Durable download request for one link. Claiming and completion happen through
 * {@link DownloadJobQueue}; this entity is used to create and re-queue jobs and to read their state.
 */
@Entity
@Table(name = "download_jobs")
public class DownloadJob {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "source_link_id", nullable = false, updatable = false)
    private UUID sourceLinkId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DownloadJobStatus status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "claim_token")
    private UUID claimToken;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DownloadJob() {
        // for JPA
    }

    public DownloadJob(UUID sourceLinkId, int maxAttempts, Instant now) {
        this.sourceLinkId = sourceLinkId;
        this.maxAttempts = maxAttempts;
        this.status = DownloadJobStatus.PENDING;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Puts a finished or failed job back in the queue with a fresh attempt budget. */
    public void requeue(int maxAttempts, Instant now) {
        this.status = DownloadJobStatus.PENDING;
        this.attempts = 0;
        this.maxAttempts = maxAttempts;
        this.nextAttemptAt = now;
        this.claimedAt = null;
        this.claimToken = null;
        this.lastError = null;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceLinkId() {
        return sourceLinkId;
    }

    public DownloadJobStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getClaimedAt() {
        return claimedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
