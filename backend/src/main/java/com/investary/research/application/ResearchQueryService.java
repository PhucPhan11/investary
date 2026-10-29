package com.investary.research.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.document.application.DocumentService;
import com.investary.document.domain.Document;
import com.investary.research.download.DownloadJob;
import com.investary.research.download.DownloadJobRepository;
import com.investary.research.extraction.ResearchItem;
import com.investary.research.extraction.ResearchItemRepository;
import com.investary.research.link.SourceLink;
import com.investary.research.link.SourceLinkRepository;
import com.investary.research.provenance.ProvenanceRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly = true)
public class ResearchQueryService {

    private final SourceLinkRepository links;
    private final DownloadJobRepository jobs;
    private final ResearchItemRepository items;
    private final ProvenanceRepository provenance;
    private final DocumentService documents;
    private final NamedParameterJdbcTemplate jdbc;
    private final JsonMapper json;

    public ResearchQueryService(SourceLinkRepository links, DownloadJobRepository jobs, ResearchItemRepository items,
                                ProvenanceRepository provenance, DocumentService documents, NamedParameterJdbcTemplate jdbc,
                                JsonMapper json) {
        this.links = links;
        this.jobs = jobs;
        this.items = items;
        this.provenance = provenance;
        this.documents = documents;
        this.jdbc = jdbc;
        this.json = json;
    }

    public List<SourceLinkView> links(UUID messageId) {
        List<SourceLink> found = links.findBySourceMessageIdOrderByPosition(messageId);
        Map<UUID, DownloadJob> jobsByLink = jobs.findBySourceLinkIdIn(found.stream().map(SourceLink::getId).toList()).stream()
                .collect(Collectors.toMap(DownloadJob::getSourceLinkId, Function.identity()));
        return found.stream().map(link -> SourceLinkView.of(link, jobsByLink.get(link.getId()))).toList();
    }

    public SourceLinkView link(UUID messageId, UUID linkId) {
        SourceLink link = links.findByIdAndSourceMessageId(linkId, messageId).orElseThrow(() -> new LinkNotFoundException(linkId));
        return SourceLinkView.of(link, jobs.findBySourceLinkId(linkId).orElse(null));
    }

    public List<ResearchItemView> researchItems(UUID messageId) {
        return items.findBySourceMessageIdOrderBySourceLocation(messageId).stream().map(this::view).toList();
    }

    public List<LinkedDocumentView> documents(UUID messageId) {
        List<ProvenanceRepository.Association> associations = provenance.findByMessage(messageId);
        Map<UUID, Document> byId = documents.findAll(associations.stream().map(ProvenanceRepository.Association::documentId).toList())
                .stream().collect(Collectors.toMap(Document::getId, Function.identity()));
        return associations.stream()
                .filter(a -> byId.containsKey(a.documentId()))
                .map(a -> {
                    Document d = byId.get(a.documentId());
                    return new LinkedDocumentView(d.getId(), d.getOriginalFilename(), d.getFileSize(), d.getBrokerageCompany(),
                            d.getReportType(), d.getTicker(), d.getPublishedOn(), d.getUploadedAt(), a.relationshipType(),
                            a.sourceLinkId(), a.sourceAttachmentId(), a.createdAt());
                })
                .toList();
    }

    public List<UUID> messageIdsForDocument(UUID documentId) {
        return provenance.findMessageIdsByDocument(documentId);
    }

    public Map<UUID, LinkStatistics> statistics(Collection<UUID> messageIds) {
        Map<UUID, LinkStatistics> result = new HashMap<>();
        if (messageIds.isEmpty()) {
            return result;
        }
        Map<UUID, Integer> documentCounts = provenance.countDocuments(messageIds);
        jdbc.query("""
                select source_message_id,
                       count(*) as total,
                       count(*) filter (where category in ('RESEARCH_REPORT', 'STOCK_RESEARCH', 'MARKET_REPORT')) as research,
                       count(*) filter (where status = 'DOWNLOADING') as downloading,
                       count(*) filter (where status = 'FAILED') as failed
                from source_links where source_message_id in (:ids) group by source_message_id""",
                Map.of("ids", messageIds),
                rs -> {
                    UUID id = rs.getObject("source_message_id", UUID.class);
                    result.put(id, new LinkStatistics(rs.getInt("total"), rs.getInt("research"),
                            documentCounts.getOrDefault(id, 0), rs.getInt("downloading"), rs.getInt("failed")));
                });
        documentCounts.forEach((id, count) -> result.putIfAbsent(id, new LinkStatistics(0, 0, count, 0, 0)));
        return result;
    }

    private ResearchItemView view(ResearchItem item) {
        JsonNode data = item.getStructuredData() == null ? null : json.readTree(item.getStructuredData());
        return new ResearchItemView(item.getId(), item.getItemType(), item.getTicker(), item.getBrokerage(), item.getPublishedAt(),
                item.getTitle(), item.getContent(), data, item.getExtractionMethod(), item.getSourceLocation(), item.getLanguage());
    }
}
