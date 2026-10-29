package com.investary.knowledge;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Supplies fake AI models instead of a real provider. Pair with
 * {@code @TestPropertySource(locations = "classpath:fake-ai.properties")} so the configured embedding
 * description matches the fake vectors.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeAiConfiguration {

    @Bean
    FakeEmbeddingModel fakeEmbeddingModel() {
        return new FakeEmbeddingModel();
    }

    @Bean
    ScriptedChatModel scriptedChatModel() {
        return new ScriptedChatModel();
    }
}
