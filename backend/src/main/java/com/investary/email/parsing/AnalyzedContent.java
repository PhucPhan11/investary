package com.investary.email.parsing;

import java.util.List;

/**
 * Derived, readable view of an email body.
 *
 * @param languages detected languages, the one with the most text first
 */
public record AnalyzedContent(String normalizedText, List<ContentBlock> blocks, List<ExtractedLink> links, List<String> languages) {
}
