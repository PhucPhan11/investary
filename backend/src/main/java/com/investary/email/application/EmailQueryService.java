package com.investary.email.application;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import com.investary.email.application.EmailExceptions.EmailNotFoundException;
import com.investary.email.application.EmailViews.Attachment;
import com.investary.email.application.EmailViews.EmailContent;
import com.investary.email.application.EmailViews.EmailDetail;
import com.investary.email.application.EmailViews.EmailSummary;
import com.investary.email.application.EmailViews.Sender;
import com.investary.email.domain.SourceMessage;
import com.investary.email.parsing.HtmlSanitizer;
import com.investary.email.persistence.SourceAttachmentRepository;
import com.investary.email.persistence.SourceMessageRepository;
import com.investary.email.persistence.SourceMessageSpecifications;
import com.investary.email.storage.EmailStorage;
import com.investary.research.application.LinkStatistics;
import com.investary.research.application.ResearchQueryService;

@Service
@Transactional(readOnly = true)
public class EmailQueryService {

    private final SourceMessageRepository messages;
    private final SourceAttachmentRepository attachments;
    private final ResearchQueryService research;
    private final HtmlSanitizer sanitizer;
    private final EmailStorage storage;

    public EmailQueryService(SourceMessageRepository messages, SourceAttachmentRepository attachments,
                             ResearchQueryService research, HtmlSanitizer sanitizer, EmailStorage storage) {
        this.messages = messages;
        this.attachments = attachments;
        this.research = research;
        this.sanitizer = sanitizer;
        this.storage = storage;
    }

    public Page<EmailSummary> list(String query, String sender, UUID documentId, Pageable pageable) {
        Specification<SourceMessage> spec = Specification.unrestricted();
        if (StringUtils.hasText(query)) {
            spec = spec.and(SourceMessageSpecifications.subjectContains(query));
        }
        if (StringUtils.hasText(sender)) {
            spec = spec.and(SourceMessageSpecifications.senderIs(sender));
        }
        if (documentId != null) {
            spec = spec.and(SourceMessageSpecifications.idIn(research.messageIdsForDocument(documentId)));
        }
        Page<SourceMessage> page = messages.findAll(spec, pageable);
        Map<UUID, LinkStatistics> statistics = research.statistics(page.getContent().stream().map(SourceMessage::getId).toList());
        return page.map(m -> new EmailSummary(m.getId(), m.getSubject(), m.getSenderName(), m.getSenderAddress(), m.getSentAt(),
                m.getImportedAt(), m.getBrokerage(), m.getParsingStatus(), statistics.getOrDefault(m.getId(), LinkStatistics.EMPTY)));
    }

    public EmailDetail get(UUID id) {
        SourceMessage m = require(id);
        List<Attachment> attachmentViews = attachments.findBySourceMessageIdOrderByPosition(id).stream().map(Attachment::of).toList();
        LinkStatistics statistics = research.statistics(List.of(id)).getOrDefault(id, LinkStatistics.EMPTY);
        List<UUID> sameMessageId = m.getMessageId() == null ? List.of() : messages.findIdsByMessageIdExcluding(m.getMessageId(), id);
        return new EmailDetail(m.getId(), m.getSubject(), m.getSenderName(), m.getSenderAddress(), m.getRecipients(),
                m.getSentAt(), m.getImportedAt(), m.getMessageId(), m.getBrokerage(), m.getDetectedLanguages(),
                m.getOriginalFilename(), m.getFileSize(), m.getContentSha256(), m.getParsingStatus(), m.getParsingError(),
                m.getHtmlContent() != null, m.getNormalizedText(), attachmentViews, statistics, sameMessageId);
    }

    /**
     * Inert HTML preview: sanitized email HTML, or the normalized text for plain-text emails.
     */
    public String safeHtml(UUID id) {
        SourceMessage m = require(id);
        if (m.getHtmlContent() != null) {
            return sanitizer.sanitize(m.getHtmlContent());
        }
        String text = m.getNormalizedText() == null ? "" : m.getNormalizedText();
        return sanitizer.sanitize("<pre style=\"white-space:pre-wrap\">" + HtmlUtils.htmlEscape(text) + "</pre>");
    }

    public Original original(UUID id) {
        SourceMessage m = require(id);
        return new Original(m.getOriginalFilename(), storage.load(m.getStorageKey()));
    }

    public List<Sender> senders() {
        return messages.findSenders().stream().map(s -> new Sender(s.getAddress(), s.getName(), s.getMessages())).toList();
    }

    /**
     * Parsed content for re-use (e.g. indexing) without re-reading the original EML.
     */
    public EmailContent content(UUID id) {
        SourceMessage m = require(id);
        return new EmailContent(m.getId(), m.getSubject(), m.getSenderName(), m.getSenderAddress(), m.getSentAt(),
                m.getBrokerage(), m.getParsingStatus(), m.getHtmlContent(), m.getNormalizedText());
    }

    /**
     * Earliest sent date of the emails that reference a document; a fallback date for reports without
     * a publication date. Empty when no email references it or none has a date.
     */
    public Optional<Instant> firstReferencedAt(UUID documentId) {
        return messages.findAllById(research.messageIdsForDocument(documentId)).stream()
                .map(SourceMessage::getSentAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder());
    }

    /**
     * Brokerage of the earliest email that references a document, if known.
     */
    public Optional<String> referencingBrokerage(UUID documentId) {
        return messages.findAllById(research.messageIdsForDocument(documentId)).stream()
                .filter(m -> m.getBrokerage() != null)
                .min(Comparator.comparing(SourceMessage::getSentAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(SourceMessage::getBrokerage);
    }

    public SourceMessage require(UUID id) {
        return messages.findById(id).orElseThrow(() -> new EmailNotFoundException(id));
    }

    public record Original(String filename, Resource content) {
    }
}
