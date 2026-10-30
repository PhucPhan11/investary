package com.investary.knowledge.assistant;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.AiExceptions.AiNotConfiguredException;
import com.investary.knowledge.assistant.PromptBuilder.LabeledHit;
import com.investary.knowledge.search.HybridSearchService;
import com.investary.knowledge.search.SearchFilters;
import com.investary.knowledge.search.SearchHit;
import com.investary.knowledge.search.SearchResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retrieval-augmented question answering over the knowledge base. Each question is answered on its own
 * (no conversation memory).
 * <ol>
 *   <li>Retrieve evidence. Without evidence the model is <b>not</b> called: the answer is INSUFFICIENT.</li>
 *   <li>Label the evidence S1..Sn in the backend and send it, size-capped, as untrusted data.</li>
 *   <li>Validate the model's answer: unknown labels are removed, figures are checked against the cited
 *       sources, and the evidence status can only go down.</li>
 *   <li>Build citations from the stored chunks, never from model text.</li>
 * </ol>
 */
@Service
public class ResearchAssistantService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ResearchAssistantService.class);

    private final HybridSearchService search;
    private final ChatGateway chat;
    private final KnowledgeProperties.Assistant settings;
    private final JsonMapper json;
    private final Clock clock;

    public ResearchAssistantService(HybridSearchService search, ObjectProvider<ChatModel> chatModels,
                                    KnowledgeProperties properties, JsonMapper json, Clock clock) {
        this.search = search;
        this.settings = properties.assistant();
        this.chat = new ChatGateway(chatModels, settings.timeout());
        this.json = json;
        this.clock = clock;
    }

    public AssistantAnswer ask(String question, SearchFilters filters, Integer maxSources) {
        if (!chat.isAvailable()) {
            throw new AiNotConfiguredException("chat");
        }
        SearchResult result = search.search(question, filters, maxSources);
        List<SearchHit> hits = result.hits();
        if (hits.isEmpty()) {
            return insufficient(result);
        }

        List<LabeledHit> labeled = Citations.label(hits, settings.maxContextChars());
        Map<String, LabeledHit> byLabel = new LinkedHashMap<>();
        labeled.forEach(hit -> byLabel.put(hit.label(), hit));
        LocalDate today = LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
        Prompt prompt = PromptBuilder.build(question, labeled, today);

        String raw = chat.call(prompt);
        ModelAnswer parsed = parse(raw);
        Map<String, String> sourceText = new LinkedHashMap<>();
        byLabel.forEach((label, hit) -> sourceText.put(label, hit.hit().content()));
        CitationValidator.Result validated = CitationValidator.validate(parsed.answer(), parsed.status(), sourceText, question);

        List<String> warnings = new ArrayList<>(parsed.warnings());
        warnings.addAll(validated.warnings());
        parsed.unsupportedPoints().forEach(point -> warnings.add("Chưa có bằng chứng: " + point));
        if (!result.coverageGaps().isEmpty()) {
            warnings.add("Không tìm thấy tài liệu đã lập chỉ mục từ: " + String.join(", ", result.coverageGaps()) + ".");
        }
        List<Citation> citations = validated.citedLabels().stream()
                .map(label -> Citations.of(byLabel.get(label), result.analysis().terms()))
                .toList();
        EvidenceStatus status = validated.status();
        if (status == EvidenceStatus.SUPPORTED && !result.coverageGaps().isEmpty()) {
            status = EvidenceStatus.PARTIAL;
        }
        log.info("Research question answered status={} retrieved={} cited={} warnings={}",
                status, hits.size(), citations.size(), warnings.size());
        return new AssistantAnswer(validated.answer(), status, citations, sourceIds(hits), result.coverageGaps(),
                List.copyOf(warnings), newest(hits), settings.modelLabel(), AssistantAnswer.DISCLAIMER);
    }

    private AssistantAnswer insufficient(SearchResult result) {
        StringBuilder answer = new StringBuilder("Không tìm thấy bằng chứng phù hợp trong các tài liệu đã được lập chỉ mục "
                + "để trả lời câu hỏi này, nên trợ lý không đưa ra câu trả lời.");
        if (!result.coverageGaps().isEmpty()) {
            answer.append(" Không có tài liệu đã lập chỉ mục từ: ").append(String.join(", ", result.coverageGaps())).append('.');
        }
        answer.append(" Bạn có thể nhập thêm báo cáo hoặc email liên quan, hoặc bỏ bớt bộ lọc.");
        return new AssistantAnswer(answer.toString(), EvidenceStatus.INSUFFICIENT, List.of(), List.of(),
                result.coverageGaps(), List.of(), null, null, AssistantAnswer.DISCLAIMER);
    }

    /**
     * Reads the model's JSON. Models sometimes wrap it in code fences or emit reasoning first; when no
     * JSON can be read the raw text is kept but treated as unverified.
     */
    ModelAnswer parse(String raw) {
        java.util.Optional<JsonNode> parsed = ChatGateway.jsonObject(raw, json);
        if (parsed.isPresent()) {
            JsonNode node = parsed.get();
            String answer = node.path("answer").asString("");
            if (!answer.isBlank()) {
                List<String> unsupported = new ArrayList<>();
                node.path("unsupportedPoints").forEach(point -> {
                    String value = point.asString("").strip();
                    if (!value.isEmpty()) {
                        unsupported.add(value);
                    }
                });
                return new ModelAnswer(answer.strip(), status(node.path("evidenceStatus").asString("")), unsupported, List.of());
            }
        }
        String text = ChatGateway.withoutThinking(raw);
        return new ModelAnswer(text.replaceAll("(?s)^```\\w*|```$", "").strip(), EvidenceStatus.PARTIAL, List.of(),
                List.of("Câu trả lời của mô hình không đúng định dạng yêu cầu; hãy kiểm tra kỹ với nguồn."));
    }

    private static EvidenceStatus status(String value) {
        try {
            return EvidenceStatus.valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return EvidenceStatus.PARTIAL;
        }
    }

    private static List<UUID> sourceIds(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::sourceId).distinct().toList();
    }

    private static LocalDate newest(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::publishedOn).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    @Override
    public void destroy() {
        chat.close();
    }

    record ModelAnswer(String answer, EvidenceStatus status, List<String> unsupportedPoints, List<String> warnings) {
    }
}
