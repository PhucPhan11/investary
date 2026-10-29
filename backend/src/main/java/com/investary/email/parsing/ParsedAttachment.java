package com.investary.email.parsing;

/**
 * A non-body MIME part. {@code content} holds the decoded bytes so PDF attachments can be imported.
 */
public record ParsedAttachment(
        int position,
        String filename,
        String contentType,
        boolean inline,
        String contentId,
        long size,
        String sha256,
        byte[] content) {
}
