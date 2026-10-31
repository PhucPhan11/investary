package com.investary.knowledge.chunking;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.extraction.TextSegment;

/**
 * Packs extracted segments (PDF lines, email blocks) into chunks of about {@code targetTokens}.
 * <ul>
 *   <li>Segments are never split unless a single one exceeds {@code maxTokens}; then it is split at
 *       sentence boundaries, and only as a last resort between words.</li>
 *   <li>Consecutive chunks of the same section overlap by roughly {@code overlapTokens}, made of whole
 *       trailing segments, so a fact on a boundary is retrievable from either side.</li>
 *   <li>A new email section always starts a new chunk (no overlap across sections), so every chunk
 *       has exactly one section for its citation. PDF chunks may span pages and record the range.</li>
 * </ul>
 */
@Component
public class TextChunker {

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?;:])\\s+");

    private final KnowledgeProperties.Chunking settings;
    private final TokenCountEstimator tokens;

    @Autowired
    public TextChunker(KnowledgeProperties properties) {
        this(properties.chunking(), new JTokkitTokenCountEstimator());
    }

    TextChunker(KnowledgeProperties.Chunking settings, TokenCountEstimator tokens) {
        if (settings.maxTokens() < settings.targetTokens() || settings.overlapTokens() >= settings.targetTokens()) {
            throw new IllegalArgumentException("Chunking requires overlap < target <= max tokens");
        }
        this.settings = settings;
        this.tokens = tokens;
    }

    public List<Chunk> chunk(List<TextSegment> segments) {
        List<Piece> pieces = new ArrayList<>();
        for (TextSegment segment : segments) {
            String text = segment.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            int count = tokens.estimate(text);
            if (count <= settings.maxTokens()) {
                pieces.add(new Piece(text, count, segment));
            } else {
                for (String part : splitOversized(text)) {
                    pieces.add(new Piece(part, tokens.estimate(part), segment));
                }
            }
        }

        List<Chunk> chunks = new ArrayList<>();
        List<Piece> current = new ArrayList<>();
        int currentTokens = 0;
        for (Piece piece : pieces) {
            boolean sectionChanged = !current.isEmpty()
                    && !Objects.equals(current.getFirst().segment().section(), piece.segment().section());
            boolean full = !current.isEmpty() && currentTokens + piece.tokens() > settings.targetTokens();
            if (sectionChanged || full) {
                chunks.add(build(chunks.size(), current));
                current = sectionChanged ? new ArrayList<>() : overlapTail(current);
                currentTokens = current.stream().mapToInt(Piece::tokens).sum();
                if (currentTokens + piece.tokens() > settings.maxTokens()) {
                    current = new ArrayList<>();
                    currentTokens = 0;
                }
            }
            current.add(piece);
            currentTokens += piece.tokens();
        }
        if (!current.isEmpty()) {
            chunks.add(build(chunks.size(), current));
        }
        return chunks;
    }

    /** Whole trailing pieces worth up to {@code overlapTokens}. */
    private List<Piece> overlapTail(List<Piece> pieces) {
        List<Piece> tail = new ArrayList<>();
        int total = 0;
        for (int i = pieces.size() - 1; i > 0; i--) { // never repeat the whole previous chunk
            Piece piece = pieces.get(i);
            if (total + piece.tokens() > settings.overlapTokens()) {
                break;
            }
            tail.addFirst(piece);
            total += piece.tokens();
        }
        return tail;
    }

    private List<String> splitOversized(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : SENTENCE_END.split(text)) {
            if (tokens.estimate(sentence) > settings.maxTokens()) {
                flush(parts, current);
                parts.addAll(splitWords(sentence));
                continue;
            }
            String candidate = current.isEmpty() ? sentence : current + " " + sentence;
            if (tokens.estimate(candidate) > settings.targetTokens() && !current.isEmpty()) {
                flush(parts, current);
                current.append(sentence);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        flush(parts, current);
        return parts;
    }

    private List<String> splitWords(String sentence) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : sentence.split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (tokens.estimate(candidate) > settings.targetTokens() && !current.isEmpty()) {
                flush(parts, current);
                current.append(word);
            } else {
                current.setLength(0);
                current.append(candidate);
            }
        }
        flush(parts, current);
        return parts;
    }

    private static void flush(List<String> parts, StringBuilder current) {
        if (!current.isEmpty()) {
            parts.add(current.toString());
            current.setLength(0);
        }
    }

    private static Chunk build(int index, List<Piece> pieces) {
        StringBuilder content = new StringBuilder();
        Integer pageStart = null;
        Integer pageEnd = null;
        String language = null;
        int total = 0;
        for (Piece piece : pieces) {
            if (!content.isEmpty()) {
                content.append('\n');
            }
            content.append(piece.text());
            total += piece.tokens();
            Integer page = piece.segment().page();
            if (page != null) {
                pageStart = pageStart == null ? page : Math.min(pageStart, page);
                pageEnd = pageEnd == null ? page : Math.max(pageEnd, page);
            }
            if (language == null) {
                language = piece.segment().language();
            }
        }
        String text = content.toString();
        return new Chunk(index, text, pageStart, pageEnd, pieces.getFirst().segment().section(), language, total,
                TickerDetector.detect(text));
    }

    private record Piece(String text, int tokens, TextSegment segment) {
    }
}
