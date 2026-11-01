package com.investary.intelligence.application;

import java.util.List;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;

import com.investary.knowledge.assistant.PromptBuilder;
import com.investary.knowledge.assistant.PromptBuilder.LabeledHit;

/**
 * Prompt of the daily brief. Instructions live only in the system message; portfolio facts, profile and
 * goals are backend data in their own elements; the evidence plan says which labels belong to which
 * holding; research text is rendered by {@link PromptBuilder#sources} as untrusted {@code <source>} elements.
 */
final class BriefPrompts {

    /** Version of the prompt and the validation rules; part of the config version and recorded on briefs. */
    static final String VERSION = "d1";

    static final String SYSTEM = """
            Bạn là trợ lý nghiên cứu của Investary. Nhiệm vụ: viết phần diễn giải cho bản tin nghiên cứu hằng ngày, chỉ dựa \
            trên các nguồn mới trong kỳ được cung cấp. Hệ thống đã tự liệt kê nguồn, ngày và trích dẫn; bạn chỉ tóm tắt.

            """ + IntelligencePrompts.COMMON_RULES + """

            8. Chỉ viết về mã có trong <holding> (danh mục) hoặc <opportunity> (chưa nắm giữ). Với mỗi mã, chỉ trích dẫn \
            các nguồn trong thuộc tính evidence của mã đó. Phần thị trường chỉ trích dẫn nguồn trong <market>; nếu không có \
            <market>, để trống marketOverview. Không mô tả diễn biến giá hay chỉ số nếu nguồn không nêu.
            9. Cơ hội nghiên cứu là gợi ý nên đọc thêm, không phải tín hiệu mua. Rủi ro và quan điểm trái chiều phải nêu \
            từng quan điểm kèm nguồn.
            10. followUps là việc nên làm tiếp như đọc báo cáo, kiểm tra dữ liệu, cập nhật giá thủ công; tuyệt đối không \
            phải lệnh mua, bán, chốt lời, cắt lỗ hay thay đổi tỷ trọng.
            11. Một nguồn có thể đã được công bố từ lâu dù mới nhận; không gọi đó là nghiên cứu mới công bố.

            Chỉ trả về một đối tượng JSON, không có văn bản nào khác:
            {"executiveSummary": "<2-4 câu có trích dẫn [Sx]>", "marketOverview": "<tóm tắt có trích dẫn hoặc rỗng>", \
            "portfolioFindings": [{"symbol": "<mã>", "finding": "<1-2 câu có trích dẫn [Sx]>"}], \
            "opportunities": [{"symbol": "<mã>", "finding": "<1 câu có trích dẫn [Sx]>"}], \
            "risks": ["<rủi ro hoặc quan điểm trái chiều có trích dẫn [Sx]>"], "followUps": ["<việc nên làm tiếp>"]}""";

    private BriefPrompts() {
    }

    /**
     * @param period  human-readable reporting period
     * @param plan    {@code <holding>}, {@code <market>} and {@code <opportunity>} elements with their evidence labels
     */
    static Prompt brief(String period, String facts, String profile, String goals, List<PlanLine> plan, List<LabeledHit> sources) {
        StringBuilder user = new StringBuilder();
        user.append("Kỳ báo cáo: ").append(period).append(". Các nguồn có thể đã cũ; hãy dựa vào ngày của từng nguồn.\n\n");
        user.append("<portfolio_facts>\n").append(facts).append("</portfolio_facts>\n\n");
        user.append("<investor_profile>\n").append(profile).append("</investor_profile>\n\n");
        user.append("<goals>\n").append(goals).append("</goals>\n\n");
        user.append("<brief_plan>\n");
        for (PlanLine line : plan) {
            user.append('<').append(line.element());
            if (line.symbol() != null) {
                user.append(" symbol=\"").append(PromptBuilder.neutralise(line.symbol()).replace('"', '\'')).append('"');
            }
            user.append(" evidence=\"").append(String.join(",", line.labels())).append("\"/>\n");
        }
        user.append("</brief_plan>\n\n");
        user.append(PromptBuilder.sources(sources));
        return new Prompt(List.of(new SystemMessage(SYSTEM), new UserMessage(user.toString())));
    }

    /**
     * @param element holding, market or opportunity
     */
    record PlanLine(String element, String symbol, List<String> labels) {
    }
}
