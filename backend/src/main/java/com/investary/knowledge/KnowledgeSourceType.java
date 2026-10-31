package com.investary.knowledge;

/**
 * Kinds of source the knowledge base indexes. Research items are not a source: they are derived from
 * email text that is already indexed, so indexing them would count the same evidence twice.
 */
public enum KnowledgeSourceType {
    DOCUMENT,
    EMAIL
}
