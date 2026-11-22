package com.investary.notebook.domain;

public enum NoteStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED;

    public boolean active() {
        return this == PENDING || this == RUNNING;
    }
}
