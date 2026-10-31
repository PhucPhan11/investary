package com.investary.knowledge.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.investary.document.TestPdfs;
import com.investary.knowledge.TestKnowledgeProperties;
import com.investary.knowledge.TestResearchPdfs;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor(TestKnowledgeProperties.defaults());

    @Test
    void extractsEachPageWithItsRealPageNumber() {
        byte[] pdf = TestResearchPdfs.textPdf(List.of(
                List.of("Báo cáo cập nhật SCS", "Khuyến nghị: Chờ mua"),
                List.of(),
                List.of("Rủi ro chính: cạnh tranh từ sân bay Long Thành", "Giá mục tiêu 78.500 đồng")));

        ExtractedText text = extractor.extract(pdf);

        assertThat(text.isSkipped()).isFalse();
        assertThat(text.pageCount()).isEqualTo(3);
        assertThat(text.segments()).extracting(TextSegment::page).containsExactly(1, 1, 3, 3);
        assertThat(text.segments()).extracting(TextSegment::text).containsExactly(
                "Báo cáo cập nhật SCS", "Khuyến nghị: Chờ mua",
                "Rủi ro chính: cạnh tranh từ sân bay Long Thành", "Giá mục tiêu 78.500 đồng");
    }

    @Test
    void keepsEnglishAndVietnameseTextIntact() {
        byte[] pdf = TestResearchPdfs.textPdf(List.of(List.of("Recommendation: Awaiting Buy", "Đánh giá: Tích cực về ngành ngân hàng")));

        assertThat(extractor.extract(pdf).segments()).extracting(TextSegment::text)
                .containsExactly("Recommendation: Awaiting Buy", "Đánh giá: Tích cực về ngành ngân hàng");
    }

    @Test
    void imageOnlyPdfIsSkippedAsNeedingOcr() {
        ExtractedText text = extractor.extract(TestResearchPdfs.imageOnlyPdf(3));

        assertThat(text.skipReason()).isEqualTo(ExtractedText.SkipReason.OCR_REQUIRED);
        assertThat(text.pageCount()).isEqualTo(3);
        assertThat(text.segments()).isEmpty();
    }

    @Test
    void pdfWithOnlyEmptyPagesIsNotAnEmptySuccess() {
        ExtractedText text = extractor.extract(TestResearchPdfs.textPdf(List.of(List.of(), List.of())));

        assertThat(text.isSkipped()).isTrue();
        assertThat(text.skipReason()).isEqualTo(ExtractedText.SkipReason.OCR_REQUIRED);
    }

    @Test
    void malformedPdfFailsWithAClearError() {
        assertThatThrownBy(() -> extractor.extract(TestPdfs.notPdf()))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("could not be read");
    }
}
