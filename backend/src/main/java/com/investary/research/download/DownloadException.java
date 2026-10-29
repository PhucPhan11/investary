package com.investary.research.download;

/**
 * A download attempt failed. The kind decides whether the job is retried.
 */
public class DownloadException extends RuntimeException {

    public enum Kind {
        /** Not allowed by policy; never retried. */
        BLOCKED,
        /** The link or its content is unusable (expired, not a PDF, too large); never retried. */
        PERMANENT,
        /** Network or server problem that may go away; retried with backoff. */
        TRANSIENT
    }

    private final Kind kind;

    private DownloadException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static DownloadException blocked(String message) {
        return new DownloadException(Kind.BLOCKED, message, null);
    }

    public static DownloadException permanent(String message) {
        return new DownloadException(Kind.PERMANENT, message, null);
    }

    public static DownloadException transientFailure(String message, Throwable cause) {
        return new DownloadException(Kind.TRANSIENT, message, cause);
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isRetryable() {
        return kind == Kind.TRANSIENT;
    }
}
