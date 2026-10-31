package com.investary.research.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.investary.document.application.DocumentDeletedEvent;
import com.investary.research.link.SourceLinkRepository;

/**
 * When a library document is deleted, links that pointed at it become downloadable again.
 * Associations in {@code message_documents} are removed by the database (ON DELETE CASCADE).
 */
@Component
public class DocumentDeletionListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentDeletionListener.class);

    private final SourceLinkRepository links;

    public DocumentDeletionListener(SourceLinkRepository links) {
        this.links = links;
    }

    @EventListener
    public void onDocumentDeleted(DocumentDeletedEvent event) {
        int detached = links.detachDocument(event.documentId());
        if (detached > 0) {
            log.info("Detached {} link(s) from deleted documentId={}", detached, event.documentId());
        }
    }
}
