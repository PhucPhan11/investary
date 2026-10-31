package com.investary.knowledge.assistant;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * @param citations          sources cited in the answer, in order of first use
 * @param retrievedSourceIds every source the retrieval step considered (cited or not)
 * @param coverageGaps       requested brokerages without indexed evidence
 * @param warnings           problems found by validation (removed citations, unverified figures, ...)
 * @param newestEvidenceDate latest source date among the retrieved evidence; the answer is not newer than this
 * @param model              chat model that wrote the answer, {@code null} when no model was called
 */
public record AssistantAnswer(String answer, EvidenceStatus evidenceStatus, List<Citation> citations,
                              List<UUID> retrievedSourceIds, List<String> coverageGaps, List<String> warnings,
                              LocalDate newestEvidenceDate, String model, String disclaimer) {

    public static final String DISCLAIMER = "Thông tin được tổng hợp từ tài liệu trong kho của bạn. Khuyến nghị của "
            + "công ty chứng khoán là ý kiến của bên thứ ba, không phải lời khuyên đầu tư của Investary. Dữ liệu có thể "
            + "đã cũ và không phản ánh giá thị trường hiện tại.";
}
