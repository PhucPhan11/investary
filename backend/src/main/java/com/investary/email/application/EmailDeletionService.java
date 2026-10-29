package com.investary.email.application;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.common.tx.AfterCommit;
import com.investary.email.application.EmailExceptions.EmailNotFoundException;
import com.investary.email.domain.SourceMessage;
import com.investary.email.persistence.SourceMessageRepository;
import com.investary.email.storage.EmailStorage;

/**
 * Retention policy: deleting an email removes the email record, its derived data (links, research
 * items, attachment metadata, download jobs and document associations, via ON DELETE CASCADE) and its
 * original EML file. Documents in the library are <b>never</b> deleted with an email, because the
 * same report may be referenced by other emails or have been kept on purpose.
 */
@Service
public class EmailDeletionService {

    private static final Logger log = LoggerFactory.getLogger(EmailDeletionService.class);

    private final SourceMessageRepository messages;
    private final EmailStorage storage;

    public EmailDeletionService(SourceMessageRepository messages, EmailStorage storage) {
        this.messages = messages;
        this.storage = storage;
    }

    @Transactional
    public void delete(UUID id) {
        SourceMessage message = messages.findById(id).orElseThrow(() -> new EmailNotFoundException(id));
        String storageKey = message.getStorageKey();
        messages.delete(message);
        messages.flush();
        AfterCommit.run(() -> {
            try {
                if (!storage.delete(storageKey)) {
                    log.warn("Original email file was already missing emailId={}", id);
                }
            } catch (RuntimeException e) {
                log.warn("Could not delete original email file emailId={}", id, e);
            }
        });
        log.info("Email deleted emailId={}", id);
    }
}
