package com.investary.knowledge.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.investary.common.storage.StorageException;
import com.investary.document.application.DocumentDownload;
import com.investary.document.application.DocumentNotFoundException;
import com.investary.document.application.DocumentService;
import com.investary.document.domain.Document;
import com.investary.email.application.EmailExceptions.EmailNotFoundException;
import com.investary.email.application.EmailQueryService;
import com.investary.email.application.EmailViews.EmailContent;
import com.investary.knowledge.KnowledgeSourceType;
import com.investary.knowledge.extraction.SourceText.DateBasis;

/**
 * Loads a source's metadata and text through the owning modules' application services.
 */
@Component
public class SourceLoader {

    /** Dates are shown to Vietnamese investors, so an email sent at 23:30 UTC belongs to the next local day. */
    static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final DocumentService documents;
    private final EmailQueryService emails;
    private final PdfTextExtractor pdfExtractor;
    private final EmailTextExtractor emailExtractor;

    public SourceLoader(DocumentService documents, EmailQueryService emails, PdfTextExtractor pdfExtractor,
                        EmailTextExtractor emailExtractor) {
        this.documents = documents;
        this.emails = emails;
        this.pdfExtractor = pdfExtractor;
        this.emailExtractor = emailExtractor;
    }

    /**
     * @return empty when the source no longer exists
     */
    public Optional<SourceText> load(KnowledgeSourceType type, UUID sourceId) {
        try {
            return Optional.of(switch (type) {
                case DOCUMENT -> loadDocument(sourceId);
                case EMAIL -> loadEmail(sourceId);
            });
        } catch (DocumentNotFoundException | EmailNotFoundException e) {
            return Optional.empty();
        }
    }

    private SourceText loadDocument(UUID id) {
        DocumentDownload download = documents.download(id);
        Document document = download.document();
        byte[] bytes;
        try (InputStream in = download.content().getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new StorageException("Failed to read stored document", e);
        }
        ExtractedText text = pdfExtractor.extract(bytes);

        LocalDate date = document.getPublishedOn();
        DateBasis basis = date == null ? null : DateBasis.PUBLISHED;
        if (date == null) {
            Optional<Instant> referenced = emails.firstReferencedAt(id);
            if (referenced.isPresent()) {
                date = toLocalDate(referenced.get());
                basis = DateBasis.EMAIL_SENT;
            }
        }
        String brokerage = document.getBrokerageCompany() != null
                ? document.getBrokerageCompany()
                : emails.referencingBrokerage(id).orElse(null);
        return new SourceText(KnowledgeSourceType.DOCUMENT, id, title(document.getOriginalFilename()), brokerage, date,
                basis, document.getTicker(), text);
    }

    private SourceText loadEmail(UUID id) {
        EmailContent email = emails.content(id);
        ExtractedText text = emailExtractor.extract(email);
        LocalDate date = email.sentAt() == null ? null : toLocalDate(email.sentAt());
        String title = email.subject() == null || email.subject().isBlank() ? "(no subject)" : email.subject().strip();
        return new SourceText(KnowledgeSourceType.EMAIL, id, title, email.brokerage(), date,
                date == null ? null : DateBasis.EMAIL_SENT, null, text);
    }

    static LocalDate toLocalDate(Instant instant) {
        return instant.atZone(MARKET_ZONE).toLocalDate();
    }

    static String title(String filename) {
        return filename.toLowerCase().endsWith(".pdf") ? filename.substring(0, filename.length() - 4) : filename;
    }
}
