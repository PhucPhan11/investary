package com.investary.knowledge.chunking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.TokenCountEstimator;

import com.investary.knowledge.IndexVersion;
import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.TestKnowledgeProperties;
import com.investary.knowledge.extraction.TextSegment;

class TextChunkerTest {

    /** One token per word keeps the expectations readable. */
    private static final TokenCountEstimator WORDS = new TokenCountEstimator() {
        @Override
        public int estimate(String text) {
            return text.isBlank() ? 0 : text.strip().split("\\s+").length;
        }

        @Override
        public int estimate(org.springframework.ai.content.MediaContent content) {
            return estimate(content.getText());
        }

        @Override
        public int estimate(Iterable<org.springframework.ai.content.MediaContent> messages) {
            int total = 0;
            for (var message : messages) {
                total += estimate(message);
            }
            return total;
        }
    };

    private final TextChunker chunker = new TextChunker(new KnowledgeProperties.Chunking(10, 12, 3), WORDS);

    @Test
    void packsWholeLinesUpToTheTargetAndOverlapsWithTrailingLines() {
        List<TextSegment> lines = List.of(
                pdf("a1 a2 a3 a4", 1), pdf("b1 b2 b3 b4", 1), pdf("c1 c2", 2), pdf("d1 d2 d3 d4", 2), pdf("e1 e2 e3", 3));

        List<Chunk> chunks = chunker.chunk(lines);

        assertThat(chunks).extracting(Chunk::content).containsExactly(
                "a1 a2 a3 a4\nb1 b2 b3 b4\nc1 c2",
                "c1 c2\nd1 d2 d3 d4\ne1 e2 e3");
        assertThat(chunks.get(0).pageStart()).isEqualTo(1);
        assertThat(chunks.get(0).pageEnd()).isEqualTo(2);
        assertThat(chunks.get(1).pageStart()).isEqualTo(2);
        assertThat(chunks.get(1).pageEnd()).isEqualTo(3);
        assertThat(chunks).extracting(Chunk::index).containsExactly(0, 1);
    }

    @Test
    void neverSplitsALineSoTableRowsStayTogether() {
        List<Chunk> chunks = chunker.chunk(List.of(
                pdf("Chỉ tiêu 2025 2026F", 1), pdf("Doanh thu 1.234 1.456", 1), pdf("LNST 210 245", 1), pdf("EPS 4.100 4.800", 1)));

        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.content().split("\n"))
                .allSatisfy(line -> assertThat(List.of("Chỉ tiêu 2025 2026F", "Doanh thu 1.234 1.456", "LNST 210 245", "EPS 4.100 4.800")).contains(line)));
    }

    @Test
    void newSectionStartsANewChunkWithoutOverlap() {
        List<Chunk> chunks = chunker.chunk(List.of(
                email("thị trường tăng", "2. Tin thị trường"), email("thanh khoản cao", "2. Tin thị trường"),
                email("SCS Chờ mua", "3. Khuyến nghị cổ phiếu")));

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).section()).isEqualTo("2. Tin thị trường");
        assertThat(chunks.get(1).section()).isEqualTo("3. Khuyến nghị cổ phiếu");
        assertThat(chunks.get(1).content()).isEqualTo("SCS Chờ mua");
        assertThat(chunks.get(1).pageStart()).isNull();
        assertThat(chunks.get(1).tickers()).containsExactly("SCS");
    }

    @Test
    void oversizedParagraphIsSplitAtSentenceBoundaries() {
        String paragraph = "Câu một có năm từ. Câu hai cũng năm từ. Câu ba thêm năm từ nữa. Câu bốn kết thúc đoạn.";

        List<Chunk> chunks = chunker.chunk(List.of(email(paragraph, null)));

        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> assertThat(WORDS.estimate(chunk.content())).isLessThanOrEqualTo(12));
        assertThat(String.join(" ", chunks.stream().map(Chunk::content).toList())).contains("Câu một").contains("kết thúc đoạn.");
    }

    @Test
    void veryLongSentenceFallsBackToWordSplits() {
        List<String> words = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            words.add("w" + i);
        }
        List<Chunk> chunks = chunker.chunk(List.of(email(String.join(" ", words), null)));

        assertThat(chunks).allSatisfy(chunk -> assertThat(WORDS.estimate(chunk.content())).isLessThanOrEqualTo(12));
        assertThat(chunks.getLast().content()).endsWith("w39");
    }

    @Test
    void emptyInputProducesNoChunks() {
        assertThat(chunker.chunk(List.of())).isEmpty();
        assertThat(chunker.chunk(List.of(pdf("   ", 1)))).isEmpty();
    }

    @Test
    void rejectsInconsistentSettings() {
        assertThatThrownBy(() -> new TextChunker(new KnowledgeProperties.Chunking(10, 8, 2), WORDS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TextChunker(new KnowledgeProperties.Chunking(10, 12, 10), WORDS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void indexVersionChangesWhenChunkingChanges() {
        String original = IndexVersion.compute(TestKnowledgeProperties.defaults());
        String changed = IndexVersion.compute(TestKnowledgeProperties.withChunking(new KnowledgeProperties.Chunking(600, 700, 80)));

        assertThat(changed).isNotEqualTo(original);
        assertThat(original).contains("fake:hashed-bag-of-words").contains("256");
    }

    @Test
    void defaultEstimatorHandlesVietnamese() {
        TextChunker real = new TextChunker(TestKnowledgeProperties.defaults());

        List<Chunk> chunks = real.chunk(List.of(pdf("Rủi ro chính của SCS là cạnh tranh từ sân bay Long Thành.", 4)));

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.tokens()).isPositive();
            assertThat(chunk.pageStart()).isEqualTo(4);
        });
    }

    private static TextSegment pdf(String text, int page) {
        return new TextSegment(text, page, null, null);
    }

    private static TextSegment email(String text, String section) {
        return new TextSegment(text, null, section, "vi");
    }
}
