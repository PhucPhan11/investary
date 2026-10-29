package com.investary.document.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import com.investary.document.domain.Document;
import com.investary.document.persistence.DocumentRepository;
import com.investary.document.storage.DocumentStorage;
import com.investary.document.storage.StorageException;

/**
 * Document use cases. Consistency rule: metadata must never point at missing content.
 * Uploads store the file first and remove it again if the metadata insert fails; deletes
 * remove the metadata first and the file only after the transaction commits.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final int MAX_FILENAME_LENGTH = 255;
    private static final String FALLBACK_FILENAME = "document.pdf";

    private final DocumentRepository repository;
    private final DocumentStorage storage;
    private final Clock clock;

    public DocumentService(DocumentRepository repository, DocumentStorage storage, Clock clock) {
        this.repository = repository;
        this.storage = storage;
        this.clock = clock;
    }

    public Document upload(UploadDocumentCommand command) {
        ContentInfo info = inspect(command);

        repository.findBySha256Checksum(info.sha256()).ifPresent(existing -> {
            throw new DuplicateDocumentException(existing.getId());
        });

        String storageKey = store(command);
        Document document = new Document(
                sanitizeFilename(command.originalFilename()),
                storageKey,
                info.size(),
                MediaType.APPLICATION_PDF_VALUE,
                info.sha256(),
                trimToNull(command.brokerageCompany()),
                command.reportType(),
                normalizeTicker(command.ticker()),
                command.publishedOn(),
                Instant.now(clock));

        try {
            Document saved = repository.saveAndFlush(document);
            log.info("Document uploaded documentId={} size={} sha256={}", saved.getId(), saved.getFileSize(), saved.getSha256Checksum());
            return saved;
        } catch (RuntimeException e) {
            discardStoredContent(storageKey);
            if (e instanceof DataIntegrityViolationException) {
                // Lost a race against a concurrent upload of the same content.
                var existing = repository.findBySha256Checksum(info.sha256());
                if (existing.isPresent()) {
                    throw new DuplicateDocumentException(existing.get().getId());
                }
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Page<Document> list(Pageable pageable) {
        return repository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Document get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public DocumentDownload download(UUID id) {
        Document document = get(id);
        return new DocumentDownload(document, storage.load(document.getStorageKey()));
    }

    @Transactional
    public void delete(UUID id) {
        Document document = get(id);
        repository.delete(document);
        String storageKey = document.getStorageKey();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteStoredContent(id, storageKey);
                }
            });
        } else {
            deleteStoredContent(id, storageKey);
        }
        log.info("Document deleted documentId={}", id);
    }

    private ContentInfo inspect(UploadDocumentCommand command) {
        try (InputStream signatureStream = command.content().getInputStream()) {
            if (!PdfSignatureValidator.isPdf(signatureStream)) {
                throw new InvalidDocumentException("Only PDF documents are accepted");
            }
        } catch (IOException e) {
            throw new StorageException("Failed to read uploaded content", e);
        }

        MessageDigest digest = sha256();
        long size;
        try (InputStream in = new DigestInputStream(command.content().getInputStream(), digest)) {
            size = in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw new StorageException("Failed to read uploaded content", e);
        }
        if (size == 0) {
            throw new InvalidDocumentException("The uploaded file is empty");
        }
        return new ContentInfo(size, HexFormat.of().formatHex(digest.digest()));
    }

    private String store(UploadDocumentCommand command) {
        try (InputStream in = command.content().getInputStream()) {
            return storage.store(in);
        } catch (IOException e) {
            throw new StorageException("Failed to read uploaded content", e);
        }
    }

    private void discardStoredContent(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException cleanupFailure) {
            log.error("Could not remove stored content after failed upload storageKey={}", storageKey, cleanupFailure);
        }
    }

    private void deleteStoredContent(UUID id, String storageKey) {
        try {
            if (!storage.delete(storageKey)) {
                log.warn("Stored content was already missing documentId={}", id);
            }
        } catch (RuntimeException e) {
            // Metadata is gone; an orphaned file is harmless and can be cleaned up later.
            log.warn("Could not delete stored content documentId={} storageKey={}", id, storageKey, e);
        }
    }

    static String sanitizeFilename(String originalFilename) {
        if (originalFilename == null) {
            return FALLBACK_FILENAME;
        }
        String name = StringUtils.getFilename(originalFilename.replace('\\', '/'));
        if (name == null) {
            return FALLBACK_FILENAME;
        }
        name = name.replaceAll("\\p{Cntrl}", "").strip();
        if (name.isEmpty()) {
            return FALLBACK_FILENAME;
        }
        return name.length() > MAX_FILENAME_LENGTH ? name.substring(0, MAX_FILENAME_LENGTH) : name;
    }

    private static String normalizeTicker(String ticker) {
        String value = trimToNull(ticker);
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record ContentInfo(long size, String sha256) {
    }
}
