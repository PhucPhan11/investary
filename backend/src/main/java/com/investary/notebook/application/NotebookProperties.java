package com.investary.notebook.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Research notebooks (Phase 6 / Milestone 2).
 *
 * @param maxNotebookSources documents and emails one notebook may hold
 * @param chatSources        chunks retrieved for one chat question
 * @param chatHistoryTurns   earlier question/answer pairs sent with a chat question, as context only
 * @param noteSources        sources in one note's evidence package; the rest are listed as omitted
 * @param chunksPerSource    chunks per source in a note's evidence package
 * @param maxContextChars    source text sent to the model for one note
 * @param dailyNoteLimit     notes per Asia/Ho_Chi_Minh day that may call the model
 * @param maxAttempts        attempts per note job
 * @param retryBackoff       first retry delay; doubles per attempt, with jitter
 * @param aiTimeout          longest wait for the model when generating a note
 */
@Validated
@ConfigurationProperties("investary.notebook")
public record NotebookProperties(
        @Min(1) @Max(1000) int maxNotebookSources,
        @Min(1) @Max(20) int chatSources,
        @Min(0) @Max(20) int chatHistoryTurns,
        @Min(1) @Max(50) int noteSources,
        @Min(1) @Max(10) int chunksPerSource,
        @Positive int maxContextChars,
        @Min(0) int dailyNoteLimit,
        @Min(1) int maxAttempts,
        @NotNull Duration retryBackoff,
        @NotNull Duration aiTimeout,
        @Valid @NotNull Worker worker) {

    /**
     * @param enabled      run the note worker (off in tests; they drive jobs with {@code claim} + {@code process})
     * @param pollInterval how often the worker looks for due notes
     * @param lease        a RUNNING note claimed longer ago than this is re-queued
     */
    public record Worker(boolean enabled, @NotNull Duration pollInterval, @NotNull Duration lease) {
    }
}
