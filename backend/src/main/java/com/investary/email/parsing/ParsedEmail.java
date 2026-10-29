package com.investary.email.parsing;

import java.time.Instant;
import java.util.List;

/**
 * Result of decoding an EML file. Bodies are decoded strings; nothing here is sanitized.
 *
 * @param problems non-fatal decoding problems; a non-empty list means the email was only partially parsed
 */
public record ParsedEmail(
        String messageId,
        String senderAddress,
        String senderName,
        List<String> recipients,
        String subject,
        Instant sentAt,
        String htmlBody,
        String textBody,
        List<ParsedAttachment> attachments,
        List<String> problems) {
}
