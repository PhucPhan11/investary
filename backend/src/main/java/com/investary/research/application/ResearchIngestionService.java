package com.investary.research.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.research.extraction.ResearchItem;
import com.investary.research.extraction.ResearchItemExtractor;
import com.investary.research.extraction.ResearchItemRepository;
import com.investary.research.link.Classification;
import com.investary.research.link.LinkClassifier;
import com.investary.research.link.SourceLink;
import com.investary.research.link.SourceLinkRepository;

import tools.jackson.databind.json.JsonMapper;

/**
 * Derives research data from a parsed source message: classified links and deterministic research items.
 * Runs inside the caller's import transaction, so a message is never stored without its links.
 */
@Service
public class ResearchIngestionService {

    private final LinkClassifier classifier;
    private final ResearchItemExtractor extractor;
    private final SourceLinkRepository links;
    private final ResearchItemRepository items;
    private final JsonMapper json;
    private final Clock clock;

    public ResearchIngestionService(LinkClassifier classifier, ResearchItemExtractor extractor, SourceLinkRepository links,
                                    ResearchItemRepository items, JsonMapper json, Clock clock) {
        this.classifier = classifier;
        this.extractor = extractor;
        this.links = links;
        this.items = items;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    public Result ingest(ResearchSource source) {
        Instant now = clock.instant();
        List<SourceLink> savedLinks = source.links().stream()
                .map(link -> {
                    Classification classification = classifier.classify(link.targetUrl(), link.host(), link.displayText(), link.section());
                    return new SourceLink(source.sourceMessageId(), link.position(), link.originalUrl(), link.targetUrl(),
                            link.host(), link.displayText(), link.section(), link.language(), source.brokerage(), classification, now);
                })
                .toList();
        links.saveAll(savedLinks);

        List<ResearchItem> savedItems = extractor.extract(source).stream()
                .map(item -> new ResearchItem(source.sourceMessageId(), item.type(), item.ticker(), source.brokerage(),
                        source.publishedAt(), item.title(), item.content(),
                        item.data().isEmpty() ? null : json.writeValueAsString(item.data()),
                        ResearchItemExtractor.METHOD, item.sourceLocation(), item.language(), now))
                .toList();
        items.saveAll(savedItems);
        return new Result(savedLinks.size(), savedItems.size());
    }

    public record Result(int links, int researchItems) {
    }
}
