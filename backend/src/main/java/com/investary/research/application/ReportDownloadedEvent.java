package com.investary.research.application;

import java.util.UUID;

/**
 * A linked report was downloaded (or matched an existing library document) and recorded for its email.
 * Published after the commit, for manual and automatic downloads alike. Listeners must not throw.
 */
public record ReportDownloadedEvent(UUID emailId, UUID documentId, UUID linkId) {
}
