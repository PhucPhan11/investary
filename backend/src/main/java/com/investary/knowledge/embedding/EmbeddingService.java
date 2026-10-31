package com.investary.knowledge.embedding;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.investary.knowledge.AiConcurrencyLimits;
import com.investary.knowledge.KnowledgeProperties;

/**
 * Provider-neutral embedding access. The concrete model comes from Spring AI auto-configuration
 * ({@code spring.ai.model.embedding}); when none is configured {@link #isAvailable()} is false and
 * indexing waits instead of failing.
 * <p>
 * Texts are sent in batches. Transient failures (timeouts, rate limits, connection errors) are retried
 * a bounded number of times with exponential backoff; the indexing job adds its own, slower retry on top.
 * Every vector is checked against the configured dimension so a model change can never store vectors
 * of a different size under the current index version.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final ObjectProvider<EmbeddingModel> models;
    private final KnowledgeProperties.Embedding settings;
    private final Sleeper sleeper;
    private final AiConcurrencyLimits limits;

    @Autowired
    public EmbeddingService(ObjectProvider<EmbeddingModel> models, KnowledgeProperties properties, AiConcurrencyLimits limits) {
        this(models, properties.embedding(), Thread::sleep, limits);
    }

    EmbeddingService(ObjectProvider<EmbeddingModel> models, KnowledgeProperties.Embedding settings, Sleeper sleeper) {
        this(models, settings, sleeper, AiConcurrencyLimits.unlimited());
    }

    EmbeddingService(ObjectProvider<EmbeddingModel> models, KnowledgeProperties.Embedding settings, Sleeper sleeper,
                     AiConcurrencyLimits limits) {
        this.models = models;
        this.settings = settings;
        this.sleeper = sleeper;
        this.limits = limits;
    }

    public boolean isAvailable() {
        return models.getIfAvailable() != null;
    }

    public String modelId() {
        return settings.modelId();
    }

    public int dimensions() {
        return settings.dimensions();
    }

    public float[] embedQuery(String text) {
        return embed(List.of(text)).getFirst();
    }

    public List<float[]> embed(List<String> texts) {
        EmbeddingModel model = models.getIfAvailable();
        if (model == null) {
            throw new EmbeddingUnavailableException();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += settings.batchSize()) {
            List<String> batch = texts.subList(start, Math.min(texts.size(), start + settings.batchSize()));
            List<float[]> result = embedBatch(model, batch);
            if (result.size() != batch.size()) {
                throw new EmbeddingException("The embedding provider returned " + result.size() + " vectors for " + batch.size() + " texts", null);
            }
            for (float[] vector : result) {
                if (vector.length != settings.dimensions()) {
                    throw new EmbeddingDimensionMismatchException(settings.modelId(), settings.dimensions(), vector.length);
                }
            }
            vectors.addAll(result);
        }
        return vectors;
    }

    private List<float[]> embedBatch(EmbeddingModel model, List<String> batch) {
        int attempt = 0;
        while (true) {
            try {
                return limitedEmbed(model, batch);
            } catch (NonTransientAiException | IllegalArgumentException e) {
                throw new EmbeddingException("The embedding provider rejected the request: " + e.getMessage(), e, true);
            } catch (RuntimeException e) {
                if (attempt >= settings.maxRetries()) {
                    throw new EmbeddingException("The embedding provider failed: " + e.getMessage(), e);
                }
                long delay = settings.retryBackoff().toMillis() * (1L << attempt);
                attempt++;
                log.warn("Embedding batch failed, retrying attempt={}/{} in {}ms: {}", attempt, settings.maxRetries(), delay, e.toString());
                try {
                    sleeper.sleep(delay);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new EmbeddingException("Interrupted while waiting to retry", interrupted);
                }
            }
        }
    }

    private List<float[]> limitedEmbed(EmbeddingModel model, List<String> batch) {
        try {
            limits.acquireEmbedding();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("Interrupted while waiting for an embedding slot", e);
        }
        try {
            return model.embed(batch);
        } finally {
            limits.releaseEmbedding();
        }
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
