package com.investary.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.investary.knowledge.FakeEmbeddingModel;
import com.investary.knowledge.KnowledgeProperties;

class EmbeddingServiceTest {

    private final FakeEmbeddingModel model = new FakeEmbeddingModel();
    private final List<Long> sleeps = new ArrayList<>();
    private final KnowledgeProperties.Embedding settings =
            new KnowledgeProperties.Embedding("fake", "hash", FakeEmbeddingModel.DIMENSIONS, 2, 2, Duration.ofMillis(100));

    @Test
    void embedsInBatches() {
        List<float[]> vectors = service(model).embed(List.of("a b", "c d", "e f", "g h", "i j"));

        assertThat(vectors).hasSize(5).allSatisfy(v -> assertThat(v).hasSize(FakeEmbeddingModel.DIMENSIONS));
        assertThat(model.calls()).isEqualTo(3);
    }

    @Test
    void retriesTransientFailuresWithExponentialBackoff() {
        model.failNext(2);

        assertThat(service(model).embed(List.of("rủi ro SCS"))).hasSize(1);
        assertThat(sleeps).containsExactly(100L, 200L);
    }

    @Test
    void givesUpAfterTheConfiguredRetries() {
        model.failNext(3);

        assertThatThrownBy(() -> service(model).embed(List.of("x"))).isInstanceOf(EmbeddingException.class);
        assertThat(model.calls()).isEqualTo(3);
    }

    @Test
    void rejectsVectorsOfAnotherDimension() {
        model.returnDimensions(1024);

        assertThatThrownBy(() -> service(model).embed(List.of("x")))
                .isInstanceOf(EmbeddingDimensionMismatchException.class)
                .hasMessageContaining("1024").hasMessageContaining("256");
    }

    @Test
    void reportsMissingModel() {
        EmbeddingService service = new EmbeddingService(new StaticListableBeanFactory().getBeanProvider(EmbeddingModel.class), settings, sleeps::add);

        assertThat(service.isAvailable()).isFalse();
        assertThatThrownBy(() -> service.embed(List.of("x"))).isInstanceOf(EmbeddingUnavailableException.class);
    }

    @Test
    void vectorTextRoundTrips() {
        float[] vector = {0.25f, -1.5f, 3e-5f};

        assertThat(PgVectors.parse(PgVectors.format(vector))).containsExactly(vector);
    }

    private EmbeddingService service(EmbeddingModel embeddingModel) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("embedding", embeddingModel);
        return new EmbeddingService(factory.getBeanProvider(EmbeddingModel.class), settings, sleeps::add);
    }
}
