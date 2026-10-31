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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.investary.knowledge.KnowledgeProperties;
import com.investary.knowledge.AiExceptions.AiNotConfiguredException;
import com.investary.knowledge.AiExceptions.AiProviderException;
import com.investary.knowledge.AiExceptions.AiTimeoutException;
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
    private static final Pattern THINKING = Pattern.compile("(?s)<think>.*?</think>");
    private static final int EXCERPT_LENGTH = 400;

    private final HybridSearchService search;
    private final ObjectProvider<ChatModel> chatModels;
    private final KnowledgeProperties.Assistant settings;
    private final JsonMapper json;
    private final Clock clock;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public ResearchAssistantService(HybridSearchService search, ObjectProvider<ChatModel> chatModels,
                                    KnowledgeProperties properties, JsonMapper json, Clock clock) {
        this.search = search;
        this.chatModels = chatModels;
        this.settings = properties.assistant();
        this.json = json;
        this.clock = clock;
    }

    public AssistantAnswer ask(String question, SearchFilters filters, Integer maxSources) {
        ChatModel model = chatModels.getIfAvailable();
        if (model == null) {
            throw new AiNotConfiguredException("chat");
        }
        SearchResult result = search.search(question, filters, maxSources);
        List<SearchHit> hits = result.hits();
        if (hits.isEmpty()) {
            return insufficient(result);
        }

        List<LabeledHit> labeled = label(hits);
        Map<String, LabeledHit> byLabel = new LinkedHashMap<>();
        labeled.forEach(hit -> byLabel.put(hit.label(), hit));
        LocalDate today = LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
        Prompt prompt = PromptBuilder.build(question, labeled, today);

        String raw = call(model, prompt);
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
                .map(label -> citation(byLabel.get(label), result.analysis().terms()))
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

    /** Labels hits S1..Sn in rank order until the context budget is used (always at least one). */
    private List<LabeledHit> label(List<SearchHit> hits) {
        List<LabeledHit> labeled = new ArrayList<>();
        int used = 0;
        for (SearchHit hit : hits) {
            int size = hit.content().length();
            if (!labeled.isEmpty() && used + size > settings.maxContextChars()) {
                continue;
            }
            used += size;
            labeled.add(new LabeledHit("S" + (labeled.size() + 1), hit));
        }
        return labeled;
    }

    private String call(ChatModel model, Prompt prompt) {
        Future<ChatResponse> future = executor.submit(() -> model.call(prompt));
        try {
            ChatResponse response = future.get(settings.timeout().toMillis(), TimeUnit.MILLISECONDS);
            String text = response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                throw new AiProviderException("The AI model returned an empty answer");
            }
            return text;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AiTimeoutException(settings.timeout().toSeconds());
        } catch (ExecutionException e) {
            log.warn("Chat model call failed: {}", e.getCause().toString());
            throw new AiProviderException("The AI model request failed (" + e.getCause().getClass().getSimpleName()
                    + "). Check that the provider is running and the model is available.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AiProviderException("Interrupted while waiting for the AI model");
        }
    }

    /**
     * Reads the model's JSON. Models sometimes wrap it in code fences or emit reasoning first; when no
     * JSON can be read the raw text is kept but treated as unverified.
     */
    ModelAnswer parse(String raw) {
        String text = THINKING.matcher(raw).replaceAll("").strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode node = json.readTree(text.substring(start, end + 1));
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
            } catch (RuntimeException e) {
                log.debug("Model output was not valid JSON: {}", e.toString());
            }
        }
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

    private static Citation citation(LabeledHit labeled, List<String> terms) {
        SearchHit hit = labeled.hit();
        return new Citation(labeled.label(), hit.sourceType(), hit.sourceId(), hit.title(), hit.brokerage(), hit.publishedOn(),
                hit.dateBasis(), hit.pageStart(), hit.pageEnd(), hit.section(), PromptBuilder.location(hit), hit.senderName(),
                hit.senderAddress(), hit.sentAt(), hit.chunkId(), Excerpts.best(hit.content(), terms, EXCERPT_LENGTH));
    }

    private static List<UUID> sourceIds(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::sourceId).distinct().toList();
    }

    private static LocalDate newest(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::publishedOn).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    record ModelAnswer(String answer, EvidenceStatus status, List<String> unsupportedPoints, List<String> warnings) {
    }
}
