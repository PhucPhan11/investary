package com.investary.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import com.investary.research.link.Keywords;

/**
 * Deterministic embedding model for tests: accent-folded words (plus a few concept groups, so that
 * "nguy cơ" and "rủi ro" land close together) hashed into a normalised bag-of-words vector.
 * It measures word overlap, not meaning; it exists to exercise the pipeline without paid API calls.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 256;

    private static final Map<String, String> CONCEPTS = Map.of(
            "nguy co", "concept_risk", "rui ro", "concept_risk", "risk", "concept_risk", "risks", "concept_risk",
            "khuyen nghi", "concept_rating", "recommendation", "concept_rating", "danh gia", "concept_rating");

    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger embeddedTexts = new AtomicInteger();
    private volatile int failuresBeforeSuccess;
    private volatile int dimensions = DIMENSIONS;

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        calls.incrementAndGet();
        if (failuresBeforeSuccess > 0) {
            failuresBeforeSuccess--;
            throw new IllegalStateException("Simulated provider outage");
        }
        List<Embedding> embeddings = new ArrayList<>();
        List<String> texts = request.getInstructions();
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(vector(texts.get(i), dimensions), i));
        }
        embeddedTexts.addAndGet(texts.size());
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return call(new EmbeddingRequest(texts, null)).getResults().stream().map(Embedding::getOutput).toList();
    }

    @Override
    public float[] embed(Document document) {
        return embed(List.of(document.getText())).getFirst();
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    public static float[] vector(String text, int dimensions) {
        String folded = " " + Keywords.normalize(text).replaceAll("[^\\p{L}\\p{N}]+", " ") + " ";
        for (Map.Entry<String, String> concept : CONCEPTS.entrySet()) {
            folded = folded.replace(" " + concept.getKey() + " ", " " + concept.getKey() + " " + concept.getValue() + " ");
        }
        float[] vector = new float[dimensions];
        for (String token : folded.strip().split(" ")) {
            if (token.length() < 2) {
                continue;
            }
            int hash = token.hashCode();
            vector[Math.floorMod(hash, dimensions)] += (hash & 1) == 0 ? 1f : -1f;
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm == 0) {
            vector[0] = 1f;
            return vector;
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < vector.length; i++) {
            vector[i] *= scale;
        }
        return vector;
    }

    /** The next {@code count} calls throw, as an unavailable provider would. */
    public void failNext(int count) {
        this.failuresBeforeSuccess = count;
    }

    /** Simulates a misconfigured model that returns vectors of another size. */
    public void returnDimensions(int value) {
        this.dimensions = value;
    }

    public int calls() {
        return calls.get();
    }

    public int embeddedTexts() {
        return embeddedTexts.get();
    }

    public void reset() {
        calls.set(0);
        embeddedTexts.set(0);
        failuresBeforeSuccess = 0;
        dimensions = DIMENSIONS;
    }
}
