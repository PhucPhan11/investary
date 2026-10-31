package com.investary.automation.application.workflow;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.investary.automation.domain.WorkflowSourceType;
import com.investary.automation.persistence.WorkflowStore.WorkflowRow;
import com.investary.common.util.Hashing;
import com.investary.document.application.DocumentNotFoundException;
import com.investary.document.application.DocumentService;
import com.investary.document.domain.Document;
import com.investary.email.application.EmailQueryService;
import com.investary.email.application.EmailViews.EmailDetail;
import com.investary.research.application.LinkedDocumentView;
import com.investary.research.application.ResearchQueryService;

/**
 * Read-only facts about a workflow's source, gathered through the owning modules' application services.
 */
@Component
public class WorkflowSources {

    private final EmailQueryService emails;
    private final ResearchQueryService research;
    private final DocumentService documents;

    public WorkflowSources(EmailQueryService emails, ResearchQueryService research, DocumentService documents) {
        this.emails = emails;
        this.research = research;
        this.documents = documents;
    }

    /** @throws com.investary.email.application.EmailExceptions.EmailNotFoundException if the email was deleted */
    public EmailDetail email(UUID id) {
        return emails.get(id);
    }

    /** @throws DocumentNotFoundException if the document was deleted */
    public Document document(UUID id) {
        return documents.get(id);
    }

    /** Library documents of an email (attachments and downloaded reports), each once. */
    public List<LinkedDocumentView> linkedDocuments(UUID emailId) {
        List<LinkedDocumentView> unique = new ArrayList<>();
        var seen = new java.util.HashSet<UUID>();
        for (LinkedDocumentView document : research.documents(emailId)) {
            if (seen.add(document.documentId())) {
                unique.add(document);
            }
        }
        return unique;
    }

    /**
     * Fingerprint of the content a workflow processed: the email's SHA-256 plus the SHA-256 of each of its
     * documents (sorted), or the document's SHA-256. It changes when a new report is downloaded, so an
     * assessment is only repeated when the research content really changed.
     */
    public String fingerprint(WorkflowRow workflow) {
        if (workflow.sourceType() == WorkflowSourceType.DOCUMENT) {
            return document(workflow.sourceId()).getSha256Checksum();
        }
        TreeSet<String> parts = new TreeSet<>();
        for (LinkedDocumentView linked : linkedDocuments(workflow.sourceId())) {
            try {
                parts.add(documents.get(linked.documentId()).getSha256Checksum());
            } catch (DocumentNotFoundException e) {
                // Deleted meanwhile: no longer part of the content.
            }
        }
        String canonical = email(workflow.sourceId()).contentSha256() + "\n" + String.join("\n", parts);
        return Hashing.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }
}
