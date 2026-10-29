package com.investary.knowledge.index;

import java.util.Map;

/**
 * Health of the knowledge base.
 *
 * @param staleSources      sources indexed with another configuration; not searched until reindexed
 * @param untrackedSources  documents and emails without index state yet (picked up automatically when auto-index is on)
 * @param searchableSources sources whose chunks are searched with the active configuration
 */
public record KnowledgeStatus(Model embedding, Model chat, String indexVersion, boolean autoIndex, boolean workerEnabled,
                              Map<KnowledgeSourceStatus, Long> sources, long staleSources, long untrackedSources,
                              long searchableSources, long searchableChunks) {

    /**
     * @param available whether a model is configured; it says nothing about whether the provider is reachable
     */
    public record Model(boolean available, String provider, String model, Integer dimensions) {
    }
}
