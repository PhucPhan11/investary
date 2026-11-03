package com.investary.automation.domain;

/** WAITING: the job is waiting for work another worker does (downloads, indexing); it uses no attempt. */
public enum JobStatus {
    PENDING,
    RUNNING,
    WAITING,
    SUCCEEDED,
    FAILED;

    public boolean isActive() {
        return this == PENDING || this == RUNNING || this == WAITING;
    }
}
