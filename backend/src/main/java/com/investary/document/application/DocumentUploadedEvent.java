package com.investary.document.application;

import java.util.UUID;

/**
 * A new document was stored and committed (not published for a duplicate upload). Listeners must not throw.
 */
public record DocumentUploadedEvent(UUID documentId, UploadOrigin origin) {
}
