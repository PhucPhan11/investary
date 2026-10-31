package com.investary.knowledge.index;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.investary.common.util.Hashing;
import com.investary.knowledge.IndexVersion;
import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.chunking.Chunk;
import com.investary.knowledge.chunking.TextChunker;
import com.investary.knowledge.embedding.EmbeddingDimensionMismatchException;
import com.investary.knowledge.embedding.EmbeddingService;
import com.investary.knowledge.extraction.ExtractedText;
import com.investary.knowledge.extraction.ExtractionException;
import com.investary.knowledge.extraction.SourceLoader;
import com.investary.knowledge.extraction.SourceText;
import com.investary.knowledge.index.KnowledgeChunkStore.PreparedChunk;
import com.investary.knowledge.index.KnowledgeIndexQueue.ClaimedSource;

/**
 * Indexes one claimed source: load metadata and text, chunk, embed, then store everything in one
 * transaction. Extraction and embedding happen outside any transaction because they can be slow.
 * Only ids and counts are logged, never source text.
 */
@Component
public class KnowledgeIndexProcessor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexProcessor.class);
    private static final int MAX_ERROR_LENGTH = 1000;

    private final SourceLoader loader;
    private final TextChunker chunker;
    private final EmbeddingService embeddings;
    private final KnowledgeChunkStore store;
    private final KnowledgeIndexQueue queue;
    private final IndexVersion version;
    private final KnowledgeProperties.Worker settings;
    private final Clock clock;

    public KnowledgeIndexProcessor(SourceLoader loader, TextChunker chunker, EmbeddingService embeddings,
                                   KnowledgeChunkStore store, KnowledgeIndexQueue queue, IndexVersion version,
                                   KnowledgeProperties properties, Clock clock) {
        this.loader = loader;
        this.chunker = chunker;
        this.embeddings = embeddings;
        this.store = store;
        this.queue = queue;
        this.version = version;
        this.settings = properties.worker();
        this.clock = clock;
    }

    public void process(ClaimedSource job) {
        String activeVersion = version.current();
        log.info("Indexing started knowledgeSourceId={} type={} sourceId={} attempt={}/{}",
                job.id(), job.type(), job.sourceId(), job.attempts(), job.maxAttempts());
        try {
            Optional<SourceText> loaded = loader.load(job.type(), job.sourceId());
            if (loaded.isEmpty()) {
                return; // the source was deleted; its index row went with it (ON DELETE CASCADE)
            }
            SourceText source = loaded.get();
            ExtractedText text = source.text();
            if (text.isSkipped()) {
                queue.skip(job, text.skipReason().name(), activeVersion, text.pageCount());
                log.info("Indexing skipped knowledgeSourceId={} reason={}", job.id(), text.skipReason());
                return;
            }
            List<Chunk> chunks = chunker.chunk(text.segments());
            if (chunks.isEmpty()) {
                queue.skip(job, ExtractedText.SkipReason.NO_TEXT.name(), activeVersion, text.pageCount());
                return;
            }

            Map<Chunk, String> inputs = new LinkedHashMap<>();
            for (Chunk chunk : chunks) {
                inputs.put(chunk, embeddingInput(source, chunk));
            }
            String contentHash = sha256(activeVersion + "\n" + String.join("\n\u0000\n", inputs.values()));
            if (activeVersion.equals(job.previousVersion()) && contentHash.equals(job.previousHash())) {
                queue.confirmUnchanged(job);
                log.info("Indexing unchanged knowledgeSourceId={} chunks={}", job.id(), chunks.size());
                return;
            }

            List<PreparedChunk> prepared = embed(inputs);
            if (!store.replace(job, source, prepared, activeVersion, version.embeddingModel(), version.dimensions(), contentHash)) {
                log.warn("Indexing finished after its claim was lost knowledgeSourceId={}; result discarded", job.id());
                return;
            }
            log.info("Indexing succeeded knowledgeSourceId={} chunks={} pages={} skippedBlocks={}",
                    job.id(), prepared.size(), text.pageCount(), text.skippedBlocks());
        } catch (ExtractionException | EmbeddingDimensionMismatchException e) {
            queue.fail(job, truncate(e.getMessage()));
            log.warn("Indexing failed permanently knowledgeSourceId={}: {}", job.id(), e.getMessage());
        } catch (RuntimeException e) {
            handleRetryable(job, e);
        }
    }

    private List<PreparedChunk> embed(Map<Chunk, String> inputs) {
        Map<Chunk, String> hashes = new LinkedHashMap<>();
        inputs.forEach((chunk, input) -> hashes.put(chunk, sha256(input)));
        Map<String, float[]> reusable = store.existingEmbeddings(hashes.values(), version.embeddingModel(), version.dimensions());

        List<Chunk> missing = hashes.keySet().stream().filter(chunk -> !reusable.containsKey(hashes.get(chunk))).toList();
        if (!missing.isEmpty()) {
            List<float[]> vectors = embeddings.embed(missing.stream().map(inputs::get).toList());
            for (int i = 0; i < missing.size(); i++) {
                reusable.put(hashes.get(missing.get(i)), vectors.get(i));
            }
        }
        List<PreparedChunk> prepared = new ArrayList<>(inputs.size());
        for (Chunk chunk : inputs.keySet()) {
            String hash = hashes.get(chunk);
            prepared.add(new PreparedChunk(chunk, hash, reusable.get(hash)));
        }
        return prepared;
    }

    private void handleRetryable(ClaimedSource job, RuntimeException e) {
        String error = truncate(e.getClass().getSimpleName() + ": " + e.getMessage());
        if (job.attempts() < job.maxAttempts()) {
            Duration backoff = settings.retryBackoff().multipliedBy(1L << Math.min(job.attempts() - 1, 10));
            queue.retryLater(job, error, clock.instant().plus(backoff));
            log.warn("Indexing failed knowledgeSourceId={} attempt={}/{}, retrying in {}: {}",
                    job.id(), job.attempts(), job.maxAttempts(), backoff, error);
        } else {
            queue.fail(job, error);
            log.warn("Indexing failed knowledgeSourceId={} after {} attempts: {}", job.id(), job.attempts(), error);
        }
    }

    /**
     * Text sent to the embedding model: the chunk with its title and section, so a chunk that only says
     * "Khuyến nghị: Mua" is still found for questions about the report it belongs to.
     */
    static String embeddingInput(SourceText source, Chunk chunk) {
        StringBuilder input = new StringBuilder();
        input.append(source.title());
        if (source.brokerage() != null) {
            input.append(" | ").append(source.brokerage());
        }
        if (chunk.section() != null) {
            input.append(" | ").append(chunk.section());
        }
        return input.append('\n').append(chunk.content()).toString();
    }

    private static String sha256(String value) {
        return Hashing.sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String message) {
        String value = message == null ? "Unknown error" : message;
        return value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
    }
}
