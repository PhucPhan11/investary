package com.investary.document.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

import com.investary.document.TestPdfs;
import com.investary.document.domain.Document;
import com.investary.document.domain.ReportType;
import com.investary.document.persistence.DocumentRepository;
import com.investary.document.storage.DocumentStorage;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T08:00:00Z");
    private static final String STORAGE_KEY = "11111111-1111-1111-1111-111111111111.pdf";

    @Mock
    DocumentRepository repository;

    @Mock
    DocumentStorage storage;

    @Mock
    ApplicationEventPublisher events;

    private DocumentService service;

    @BeforeEach
    void setUp() {
        service = new DocumentService(repository, storage, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void uploadStoresContentAndPersistsMetadata() throws Exception {
        byte[] content = TestPdfs.pdf("upload");
        when(repository.findBySha256Checksum(anyString())).thenReturn(Optional.empty());
        when(storage.store(any(InputStream.class))).thenReturn(STORAGE_KEY);
        when(repository.saveAndFlush(any(Document.class))).thenAnswer(inv -> withId(inv.getArgument(0), UUID.randomUUID()));

        Document saved = service.upload(new UploadDocumentCommand(new ByteArrayResource(content), "C:\\reports\\ VCB Q3.pdf ",
                "  SSI Research ", ReportType.STOCK_ANALYSIS, " vcb ", LocalDate.of(2026, 10, 1)));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getOriginalFilename()).isEqualTo("VCB Q3.pdf");
        assertThat(saved.getStorageKey()).isEqualTo(STORAGE_KEY);
        assertThat(saved.getFileSize()).isEqualTo(content.length);
        assertThat(saved.getMimeType()).isEqualTo("application/pdf");
        assertThat(saved.getSha256Checksum()).isEqualTo(sha256(content));
        assertThat(saved.getBrokerageCompany()).isEqualTo("SSI Research");
        assertThat(saved.getTicker()).isEqualTo("VCB");
        assertThat(saved.getReportType()).isEqualTo(ReportType.STOCK_ANALYSIS);
        assertThat(saved.getUploadedAt()).isEqualTo(NOW);
    }

    @Test
    void uploadRejectsNonPdfContent() {
        var command = command(TestPdfs.notPdf());

        assertThatThrownBy(() -> service.upload(command))
                .isInstanceOf(InvalidDocumentException.class)
                .hasMessageContaining("PDF");
        verify(storage, never()).store(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void uploadRejectsDuplicateContentWithoutStoringIt() {
        byte[] content = TestPdfs.pdf("dup");
        UUID existingId = UUID.randomUUID();
        when(repository.findBySha256Checksum(sha256(content)))
                .thenReturn(Optional.of(withId(document(), existingId)));

        assertThatThrownBy(() -> service.upload(command(content)))
                .isInstanceOfSatisfying(DuplicateDocumentException.class,
                        e -> assertThat(e.getExistingDocumentId()).isEqualTo(existingId));
        verify(storage, never()).store(any());
    }

    @Test
    void uploadRemovesStoredFileWhenDatabaseInsertFails() {
        when(repository.findBySha256Checksum(anyString())).thenReturn(Optional.empty());
        when(storage.store(any(InputStream.class))).thenReturn(STORAGE_KEY);
        when(repository.saveAndFlush(any(Document.class))).thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.upload(command(TestPdfs.pdf("db-fail"))))
                .isInstanceOf(IllegalStateException.class);
        verify(storage).delete(STORAGE_KEY);
    }

    @Test
    void uploadReportsDuplicateWhenConcurrentInsertWins() {
        UUID winnerId = UUID.randomUUID();
        when(repository.findBySha256Checksum(anyString()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(withId(document(), winnerId)));
        when(storage.store(any(InputStream.class))).thenReturn(STORAGE_KEY);
        when(repository.saveAndFlush(any(Document.class))).thenThrow(new DataIntegrityViolationException("unique"));

        assertThatThrownBy(() -> service.upload(command(TestPdfs.pdf("race"))))
                .isInstanceOfSatisfying(DuplicateDocumentException.class,
                        e -> assertThat(e.getExistingDocumentId()).isEqualTo(winnerId));
        verify(storage).delete(STORAGE_KEY);
    }

    @Test
    void getThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(id)).isInstanceOf(DocumentNotFoundException.class);
    }

    @Test
    void downloadReturnsMetadataAndContent() {
        UUID id = UUID.randomUUID();
        Document document = withId(document(), id);
        Resource content = new ByteArrayResource(TestPdfs.pdf("download"));
        when(repository.findById(id)).thenReturn(Optional.of(document));
        when(storage.load(STORAGE_KEY)).thenReturn(content);

        DocumentDownload download = service.download(id);

        assertThat(download.document()).isSameAs(document);
        assertThat(download.content()).isSameAs(content);
    }

    @Test
    void deleteRemovesMetadataAndFile() {
        UUID id = UUID.randomUUID();
        Document document = withId(document(), id);
        when(repository.findById(id)).thenReturn(Optional.of(document));
        when(storage.delete(STORAGE_KEY)).thenReturn(true);

        service.delete(id);

        verify(repository).delete(document);
        verify(storage).delete(STORAGE_KEY);
        verify(events).publishEvent(new DocumentDeletedEvent(id));
    }

    @Test
    void deleteSucceedsEvenIfFileRemovalFails() {
        UUID id = UUID.randomUUID();
        Document document = withId(document(), id);
        when(repository.findById(id)).thenReturn(Optional.of(document));
        when(storage.delete(STORAGE_KEY)).thenThrow(new com.investary.common.storage.StorageException("locked"));

        service.delete(id);

        verify(repository).delete(document);
    }

    @Test
    void deleteThrowsWhenMissing() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(id)).isInstanceOf(DocumentNotFoundException.class);
        verify(storage, never()).delete(anyString());
    }

    @Test
    void sanitizeFilenameStripsPathsAndControlCharacters() {
        assertThat(DocumentService.sanitizeFilename("../../etc/passwd.pdf")).isEqualTo("passwd.pdf");
        assertThat(DocumentService.sanitizeFilename("a\u0000b\nc.pdf")).isEqualTo("abc.pdf");
        assertThat(DocumentService.sanitizeFilename("   ")).isEqualTo("document.pdf");
        assertThat(DocumentService.sanitizeFilename(null)).isEqualTo("document.pdf");
        assertThat(DocumentService.sanitizeFilename("x".repeat(300) + ".pdf")).hasSize(255);
    }

    @Test
    void persistedDocumentNeverCarriesClientDeclaredMimeType() {
        when(repository.findBySha256Checksum(anyString())).thenReturn(Optional.empty());
        when(storage.store(any(InputStream.class))).thenReturn(STORAGE_KEY);
        when(repository.saveAndFlush(any(Document.class))).thenAnswer(inv -> inv.getArgument(0));

        service.upload(command(TestPdfs.pdf("mime")));

        ArgumentCaptor<Document> captor = ArgumentCaptor.forClass(Document.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getMimeType()).isEqualTo("application/pdf");
    }

    private static UploadDocumentCommand command(byte[] content) {
        return new UploadDocumentCommand(new ByteArrayResource(content), "report.pdf", null, null, null, null);
    }

    private static Document document() {
        return new Document("report.pdf", STORAGE_KEY, 10, "application/pdf", "0".repeat(64),
                null, ReportType.OTHER, null, null, NOW);
    }

    private static Document withId(Document document, UUID id) {
        ReflectionTestUtils.setField(document, "id", id);
        return document;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
