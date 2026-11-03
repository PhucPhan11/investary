package com.investary.email.application;

import java.util.UUID;

import com.investary.email.domain.SourceType;

/**
 * A new email was imported. Published after the email, its research data and its PDF attachments are
 * committed, outside any transaction; duplicates publish nothing. Listeners must not throw.
 */
public record EmailImportedEvent(UUID emailId, SourceType sourceType) {
}
