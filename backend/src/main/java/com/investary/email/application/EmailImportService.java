package com.investary.email.application;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import com.investary.common.storage.StorageException;
import com.investary.common.util.Hashing;
import com.investary.document.application.DocumentService;
import com.investary.document.application.DuplicateDocumentException;
import com.investary.document.application.PdfSignatureValidator;
import com.investary.document.application.UploadDocumentCommand;
import com.investary.document.application.UploadOrigin;
import com.investary.email.EmailProperties;
import com.investary.email.application.EmailExceptions.DuplicateEmailException;
import com.investary.email.application.EmailExceptions.EmailTooLargeException;
import com.investary.email.application.EmailExceptions.InvalidEmailException;
import com.investary.email.domain.ParsingStatus;
import com.investary.email.domain.SourceAttachment;
import com.investary.email.domain.SourceMessage;
import com.investary.email.domain.SourceType;
import com.investary.email.parsing.AnalyzedContent;
import com.investary.email.parsing.ContentAnalyzer;
import com.investary.email.parsing.EmlParser;
import com.investary.email.parsing.MalformedEmailException;
import com.investary.email.parsing.ParsedAttachment;
import com.investary.email.parsing.ParsedEmail;
import com.investary.email.persistence.SourceAttachmentRepository;
import com.investary.email.persistence.SourceMessageRepository;
import com.investary.email.storage.EmailStorage;
import com.investary.research.application.ResearchIngestionService;
import com.investary.research.application.ResearchSource;
import com.investary.research.link.BrokerageRegistry;
import com.investary.research.provenance.ProvenanceRepository;
import com.investary.research.provenance.ProvenanceRepository.RelationshipType;

/**
 * Imports an EML file:
 * <ol>
 *   <li>validate type and size, and reject byte-identical re-imports (SHA-256)</li>
 *   <li>parse headers and bodies; reject input that is not an email at all</li>
 *   <li>store the original file, then persist metadata, attachments, links and research items in one transaction
 *       (the stored file is removed again if that transaction fails)</li>
 *   <li>after commit, import PDF attachments into the document library and link them to the email</li>
 * </ol>
 */
@Service
public class EmailImportService {

    private static final Logger log = LoggerFactory.getLogger(EmailImportService.class);
    private static final int MAX_ERROR_LENGTH = 2000;

    private final EmlParser parser;
    private final ContentAnalyzer analyzer;
    private final EmailStorage storage;
    private final SourceMessageRepository messages;
    private final SourceAttachmentRepository attachments;
    private final ResearchIngestionService research;
    private final BrokerageRegistry brokerages;
    private final DocumentService documents;
    private final ProvenanceRepository provenance;
    private final TransactionTemplate transactions;
    private final EmailProperties properties;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public EmailImportService(EmlParser parser, ContentAnalyzer analyzer, EmailStorage storage, SourceMessageRepository messages,
                              SourceAttachmentRepository attachments, ResearchIngestionService research,
                              BrokerageRegistry brokerages, DocumentService documents, ProvenanceRepository provenance,
                              TransactionTemplate transactions, EmailProperties properties, ApplicationEventPublisher events,
                              Clock clock) {
        this.parser = parser;
        this.analyzer = analyzer;
        this.storage = storage;
        this.messages = messages;
        this.attachments = attachments;
        this.research = research;
        this.brokerages = brokerages;
        this.documents = documents;
        this.provenance = provenance;
        this.transactions = transactions;
        this.properties = properties;
        this.events = events;
        this.clock = clock;
    }

    public UUID importEml(MultipartFile file) {
        validateUpload(file);
        byte[] raw;
        try {
            raw = file.getBytes();
        } catch (IOException e) {
            throw new StorageException("Failed to read uploaded email", e);
        }
        return importEml(raw, SourceType.EML_UPLOAD, file.getOriginalFilename());
    }

    /**
     * Imports the original RFC 822 bytes of a message fetched by another module (e.g. Gmail sync), through
     * the same pipeline as an upload: size limit, SHA-256 duplicate check, parsing, storage and research data.
     *
     * @throws DuplicateEmailException if identical bytes were imported before
     * @throws EmailTooLargeException  if the message exceeds {@code investary.email.max-size}
     * @throws InvalidEmailException   if the bytes are not an email
     */
    public UUID importRaw(byte[] raw, SourceType sourceType, String filename) {
        if (raw.length > properties.maxSize().toBytes()) {
            throw new EmailTooLargeException(properties.maxSize().toMegabytes());
        }
        return importEml(raw, sourceType, filename);
    }

    UUID importEml(byte[] raw, String originalFilename) {
        return importEml(raw, SourceType.EML_UPLOAD, originalFilename);
    }

    private UUID importEml(byte[] raw, SourceType sourceType, String originalFilename) {
        if (raw.length == 0) {
            throw new InvalidEmailException("The uploaded file is empty");
        }
        String sha256 = Hashing.sha256(raw);
        messages.findByContentSha256(sha256).ifPresent(existing -> {
            throw new DuplicateEmailException(existing.getId());
        });

        ParsedEmail parsed;
        try {
            parsed = parser.parse(new ByteArrayInputStream(raw));
        } catch (MalformedEmailException e) {
            throw new InvalidEmailException(e.getMessage());
        }
        Instant now = clock.instant();
        String brokerage = brokerages.identifySender(parsed.senderAddress()).orElse(null);
        Analysis analysis = analyze(parsed);

        String storageKey = storage.store(raw, now);
        UUID messageId;
        try {
            messageId = transactions.execute(status -> persist(parsed, analysis, sourceType, raw.length, sha256, storageKey,
                    sanitizeFilename(originalFilename), brokerage, now));
        } catch (RuntimeException e) {
            discardStoredFile(storageKey);
            if (e instanceof DataIntegrityViolationException) {
                var existing = messages.findByContentSha256(sha256);
                if (existing.isPresent()) {
                    throw new DuplicateEmailException(existing.get().getId());
                }
            }
            throw e;
        }
        log.info("Email imported emailId={} sourceType={} size={} parsingStatus={} links={} attachments={}",
                messageId, sourceType, raw.length, analysis.status(), analysis.content().links().size(), parsed.attachments().size());

        importPdfAttachments(messageId, parsed.attachments(), brokerage);
        publishImported(messageId, sourceType);
        return messageId;
    }

    private void publishImported(UUID messageId, SourceType sourceType) {
        try {
            events.publishEvent(new EmailImportedEvent(messageId, sourceType));
        } catch (RuntimeException e) {
            // The import is committed; follow-up processing has its own reconciliation.
            log.error("Email imported event failed emailId={}", messageId, e);
        }
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidEmailException("The uploaded file is empty");
        }
        long max = properties.maxSize().toBytes();
        if (file.getSize() > max) {
            throw new EmailTooLargeException(properties.maxSize().toMegabytes());
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".eml") && !type.startsWith("message/rfc822")) {
            throw new InvalidEmailException("Only .eml files (message/rfc822) are supported");
        }
    }

    private Analysis analyze(ParsedEmail parsed) {
        List<String> problems = new ArrayList<>(parsed.problems());
        AnalyzedContent content;
        ParsingStatus status;
        try {
            if (parsed.htmlBody() != null) {
                content = analyzer.analyzeHtml(parsed.htmlBody());
            } else if (parsed.textBody() != null) {
                content = analyzer.analyzePlainText(parsed.textBody());
            } else {
                content = new AnalyzedContent("", List.of(), List.of(), List.of());
                if (parsed.attachments().isEmpty()) {
                    problems.add("The email has no readable body");
                }
            }
            status = problems.isEmpty() ? ParsingStatus.PARSED : ParsingStatus.PARTIAL;
        } catch (RuntimeException e) {
            log.warn("Email content could not be analysed", e);
            content = new AnalyzedContent(parsed.textBody() == null ? "" : parsed.textBody(), List.of(), List.of(), List.of());
            problems.add("Content analysis failed: " + e.getClass().getSimpleName());
            status = ParsingStatus.FAILED;
        }
        String error = problems.isEmpty() ? null : truncate(String.join("; ", problems));
        return new Analysis(content, status, error);
    }

    private UUID persist(ParsedEmail parsed, Analysis analysis, SourceType sourceType, long size, String sha256, String storageKey,
                         String originalFilename, String brokerage, Instant now) {
        SourceMessage message = new SourceMessage(sourceType, storageKey, originalFilename, size, sha256,
                truncate(parsed.messageId(), 998), truncate(parsed.senderAddress(), 320), truncate(parsed.senderName(), 500),
                parsed.recipients(), parsed.subject(), parsed.sentAt(), now);
        message.applyContent(brokerage, analysis.content().languages(), parsed.htmlBody(),
                analysis.content().normalizedText(), analysis.status(), analysis.error());
        message = messages.saveAndFlush(message);
        UUID id = message.getId();

        for (ParsedAttachment attachment : parsed.attachments()) {
            attachments.save(new SourceAttachment(id, attachment.position(), truncate(attachment.filename(), 255),
                    truncate(attachment.contentType(), 255), attachment.size(), attachment.sha256(), attachment.inline(),
                    truncate(attachment.contentId(), 255)));
        }

        AnalyzedContent content = analysis.content();
        research.ingest(new ResearchSource(id, brokerage, parsed.sentAt(),
                content.blocks().stream()
                        .map(b -> new ResearchSource.Block(b.index(), b.text(), b.headingLevel(), b.section(), b.lead(), b.language()))
                        .toList(),
                content.links().stream()
                        .map(l -> new ResearchSource.Link(l.position(), l.originalUrl(), l.targetUrl(), l.host(),
                                l.displayText(), truncate(l.section(), 500), l.language()))
                        .toList()));
        return id;
    }

    /**
     * PDF attachments are local content (no network access involved), so they go straight into the
     * document library. Failures are recorded on the attachment and do not undo the email import.
     */
    private void importPdfAttachments(UUID messageId, List<ParsedAttachment> parsedAttachments, String brokerage) {
        List<SourceAttachment> stored = attachments.findBySourceMessageIdOrderByPosition(messageId);
        for (SourceAttachment attachment : stored) {
            ParsedAttachment parsed = parsedAttachments.stream()
                    .filter(p -> p.position() == attachment.getPosition()).findFirst().orElse(null);
            if (parsed == null || !isPdf(parsed.content())) {
                continue;
            }
            UUID documentId;
            String error = null;
            try {
                String name = parsed.filename() == null ? "attachment.pdf" : parsed.filename();
                documentId = documents.upload(new UploadDocumentCommand(new ByteArrayResource(parsed.content()), name,
                        brokerage, null, null, null, UploadOrigin.EMAIL_ATTACHMENT)).getId();
            } catch (DuplicateDocumentException e) {
                documentId = e.getExistingDocumentId();
            } catch (RuntimeException e) {
                log.warn("PDF attachment could not be imported emailId={} position={}", messageId, attachment.getPosition(), e);
                documentId = null;
                error = "The PDF attachment could not be added to the document library";
            }
            UUID linkedDocument = documentId;
            String importError = error;
            transactions.executeWithoutResult(status -> attachments.findById(attachment.getId()).ifPresent(current -> {
                if (linkedDocument != null) {
                    current.linkDocument(linkedDocument);
                    provenance.record(messageId, linkedDocument, RelationshipType.ATTACHMENT, null, current.getId());
                } else {
                    current.recordImportError(importError);
                }
            }));
        }
    }

    private static boolean isPdf(byte[] content) {
        try {
            return PdfSignatureValidator.isPdf(new ByteArrayInputStream(content));
        } catch (IOException e) {
            return false;
        }
    }

    private void discardStoredFile(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException e) {
            log.error("Could not remove stored email after failed import storageKey={}", storageKey, e);
        }
    }

    static String sanitizeFilename(String name) {
        if (name == null) {
            return "email.eml";
        }
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("\\p{Cntrl}", "").strip();
        if (base.isEmpty()) {
            return "email.eml";
        }
        return base.length() > 255 ? base.substring(0, 255) : base;
    }

    private static String truncate(String value) {
        return truncate(value, MAX_ERROR_LENGTH);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private record Analysis(AnalyzedContent content, ParsingStatus status, String error) {
    }
}
