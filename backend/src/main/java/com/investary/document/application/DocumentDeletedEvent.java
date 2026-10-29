package com.investary.document.application;

import java.util.UUID;

/**
 * Published inside the deleting transaction, before the document row is removed, so other
 * modules can detach their references without the document module depending on them.
 */
public record DocumentDeletedEvent(UUID documentId) {
}
