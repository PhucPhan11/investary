package com.investary.email.api;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.investary.common.web.PageResponse;
import com.investary.email.application.EmailDeletionService;
import com.investary.email.application.EmailImportService;
import com.investary.email.application.EmailQueryService;
import com.investary.email.application.EmailViews.EmailDetail;
import com.investary.email.application.EmailViews.EmailSummary;
import com.investary.email.application.EmailViews.Sender;
import com.investary.email.parsing.HtmlSanitizer;
import com.investary.research.application.DownloadRequestService;
import com.investary.research.application.LinkedDocumentView;
import com.investary.research.application.ResearchItemView;
import com.investary.research.application.ResearchQueryService;
import com.investary.research.application.SourceLinkView;

@RestController
@RequestMapping("/api/v1/emails")
public class EmailController {

    private static final MediaType MESSAGE_RFC822 = MediaType.parseMediaType("message/rfc822");

    private final EmailImportService importService;
    private final EmailQueryService queryService;
    private final EmailDeletionService deletionService;
    private final ResearchQueryService research;
    private final DownloadRequestService downloads;

    public EmailController(EmailImportService importService, EmailQueryService queryService, EmailDeletionService deletionService,
                           ResearchQueryService research, DownloadRequestService downloads) {
        this.importService = importService;
        this.queryService = queryService;
        this.deletionService = deletionService;
        this.research = research;
        this.downloads = downloads;
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<EmailDetail> importEml(@RequestPart("file") MultipartFile file) {
        UUID id = importService.importEml(file);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/v1/emails/{id}").buildAndExpand(id).toUri();
        return ResponseEntity.created(location).body(queryService.get(id));
    }

    @GetMapping
    public PageResponse<EmailSummary> list(
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(required = false) String sender,
            @RequestParam(required = false) UUID documentId,
            @PageableDefault(size = 20, sort = "importedAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(queryService.list(query, sender, documentId, pageable), summary -> summary);
    }

    @GetMapping("/senders")
    public List<Sender> senders() {
        return queryService.senders();
    }

    @GetMapping("/{id}")
    public EmailDetail get(@PathVariable UUID id) {
        return queryService.get(id);
    }

    /**
     * Sanitized, inert HTML preview meant for a sandboxed iframe. The CSP blocks every remote resource.
     */
    @GetMapping(path = "/{id}/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> html(@PathVariable UUID id) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header("Content-Security-Policy", HtmlSanitizer.CONTENT_SECURITY_POLICY + "; frame-ancestors 'self'")
                .header("X-Content-Type-Options", "nosniff")
                .header("Referrer-Policy", "no-referrer")
                .cacheControl(CacheControl.noStore())
                .body(queryService.safeHtml(id));
    }

    @GetMapping("/{id}/original")
    public ResponseEntity<Resource> original(@PathVariable UUID id) throws IOException {
        EmailQueryService.Original original = queryService.original(id);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(original.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MESSAGE_RFC822)
                .contentLength(original.content().contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(original.content());
    }

    @GetMapping("/{id}/links")
    public List<SourceLinkView> links(@PathVariable UUID id) {
        queryService.require(id);
        return research.links(id);
    }

    @GetMapping("/{id}/links/{linkId}")
    public SourceLinkView link(@PathVariable UUID id, @PathVariable UUID linkId) {
        queryService.require(id);
        return research.link(id, linkId);
    }

    /**
     * Queues a background download of the linked report (also used to retry a failed one).
     * Returns 202 while queued or running and 200 when the report is already in the library.
     */
    @PostMapping("/{id}/links/{linkId}/download")
    public ResponseEntity<SourceLinkView> download(@PathVariable UUID id, @PathVariable UUID linkId) {
        queryService.require(id);
        boolean accepted = downloads.request(id, linkId);
        SourceLinkView view = research.link(id, linkId);
        if (!accepted) {
            return ResponseEntity.ok(view);
        }
        URI status = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/emails/{id}/links/{linkId}").buildAndExpand(id, linkId).toUri();
        return ResponseEntity.accepted().location(status).body(view);
    }

    @GetMapping("/{id}/documents")
    public List<LinkedDocumentView> documents(@PathVariable UUID id) {
        queryService.require(id);
        return research.documents(id);
    }

    @GetMapping("/{id}/research-items")
    public List<ResearchItemView> researchItems(@PathVariable UUID id) {
        queryService.require(id);
        return research.researchItems(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        deletionService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
