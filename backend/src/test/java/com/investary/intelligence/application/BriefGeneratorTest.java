package com.investary.intelligence.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.retry.NonTransientAiException;

import com.investary.intelligence.IntelligenceTestData;
import com.investary.intelligence.application.BriefEvidenceCollector.BriefEvidence;
import com.investary.intelligence.application.BriefEvidenceCollector.Entry;
import com.investary.intelligence.application.BriefGenerator.Draft;
import com.investary.intelligence.domain.BriefAiStatus;
import com.investary.intelligence.domain.BriefSourceRole;
import com.investary.intelligence.domain.BriefStatus;
import com.investary.intelligence.domain.BriefTrigger;
import com.investary.intelligence.domain.ResearchState;
import com.investary.intelligence.persistence.BriefJobQueue.ClaimedJob;
import com.investary.intelligence.persistence.BriefStore;
import com.investary.intelligence.persistence.BriefStore.BriefRow;
import com.investary.investor.application.ProfileView;
import com.investary.knowledge.AiConcurrencyLimits;
import com.investary.knowledge.IndexVersion;
import com.investary.knowledge.ScriptedChatModel;
import com.investary.knowledge.TestKnowledgeProperties;

import tools.jackson.databind.json.JsonMapper;

class BriefGeneratorTest {

    private static final Instant START = Instant.parse("2026-10-31T01:00:00Z");
    private static final Instant END = Instant.parse("2026-11-01T01:00:00Z");
    private static final ProfileView NO_PROFILE = new ProfileView(false, null, null, null, null, List.of(), null, null, null, null);

    private final BriefEvidenceCollector collector = mock(BriefEvidenceCollector.class);
    private final BriefStore store = mock(BriefStore.class);
    private final ScriptedChatModel chatModel = new ScriptedChatModel();
    private final JsonMapper json = JsonMapper.builder().build();
    private BriefGenerator generator;

    private BriefGenerator generator(BriefProperties.NoResearchMode mode, ChatModel model, Duration timeout) {
        BriefProperties properties = new BriefProperties(true, new BriefProperties.Schedule("0 0 8 * * *", "Asia/Ho_Chi_Minh"),
                Duration.ofHours(12), mode, 12, 2, 14000, 7, 5, 3, Duration.ofMillis(1), timeout,
                new BriefProperties.Worker(false, 1, Duration.ofSeconds(1), Duration.ofMinutes(15)));
        var knowledge = TestKnowledgeProperties.defaults();
        generator = new BriefGenerator(collector, store, properties, knowledge, new IndexVersion(knowledge),
                IntelligenceTestData.provider(model), AiConcurrencyLimits.unlimited(), json,
                Clock.fixed(Instant.parse("2026-11-01T02:00:00Z"), ZoneOffset.UTC));
        return generator;
    }

    @AfterEach
    void close() {
        if (generator != null) {
            generator.destroy();
        }
    }

    private static ClaimedJob job(int attempt) {
        return new ClaimedJob(UUID.randomUUID(), BriefTrigger.SCHEDULED, IntelligenceTestData.PORTFOLIO, START, END, "Asia/Ho_Chi_Minh",
                LocalDate.parse("2026-11-01"), false, null, "b-test", attempt, 3, UUID.randomUUID());
    }

    private static BriefEvidence evidence(ResearchState state, List<Entry> entries) {
        return new BriefEvidence(IntelligenceTestData.PORTFOLIO, IntelligenceTestData.snapshot(),
                IntelligenceTestData.snapshot().holdings(), entries, List.of(), List.of(), Map.of(), NO_PROFILE, List.of(), Map.of(), state);
    }

    private void evidenceIs(BriefEvidence evidence) {
        when(collector.collect(eq(IntelligenceTestData.PORTFOLIO), eq(START), eq(END), any(), any())).thenReturn(evidence);
        when(store.head(any(), any(), any())).thenReturn(Optional.empty());
    }

    private static List<Entry> fptEntry() {
        return List.of(BriefValidatorTest.entry("TCBS", "FPT", "S1", "FPT: lợi nhuận quý 3 tăng mạnh nhờ mảng công nghệ."));
    }

    @Test
    void noMaterialResearchIsAMinimalBriefWithoutAModelCall() {
        evidenceIs(evidence(ResearchState.NO_MATERIAL_RESEARCH, List.of()));
        Draft draft = generator(BriefProperties.NoResearchMode.MINIMAL, chatModel, Duration.ofSeconds(5)).generate(job(1));

        assertThat(draft.status()).isEqualTo(BriefStatus.COMPLETED);
        assertThat(draft.researchState()).isEqualTo(ResearchState.NO_MATERIAL_RESEARCH);
        assertThat(draft.aiStatus()).isEqualTo(BriefAiStatus.SKIPPED_NO_EVIDENCE);
        assertThat(draft.contentJson()).contains("không có nghiên cứu mới đáng kể");
        assertThat(chatModel.prompts()).isEmpty();
    }

    @Test
    void skipModeRecordsASkippedBrief() {
        evidenceIs(evidence(ResearchState.NO_MATERIAL_RESEARCH, List.of()));
        Draft draft = generator(BriefProperties.NoResearchMode.SKIP, chatModel, Duration.ofSeconds(5)).generate(job(1));

        assertThat(draft.status()).isEqualTo(BriefStatus.SKIPPED);
        assertThat(chatModel.prompts()).isEmpty();
    }

    @Test
    void unchangedInputsReuseTheLatestRevision() {
        evidenceIs(evidence(ResearchState.NEW_RESEARCH, fptEntry()));
        chatModel.reply("{\"executiveSummary\": \"Lợi nhuận FPT tăng mạnh [S1].\"}");
        BriefGenerator generator = generator(BriefProperties.NoResearchMode.MINIMAL, chatModel, Duration.ofSeconds(5));
        Draft first = generator.generate(job(1));
        assertThat(first.status()).isEqualTo(BriefStatus.COMPLETED);
        assertThat(first.sources()).extracting(s -> s.role()).containsExactly(BriefSourceRole.CITED);

        BriefRow head = mock(BriefRow.class);
        when(head.id()).thenReturn(UUID.randomUUID());
        when(head.inputFingerprint()).thenReturn(first.inputFingerprint());
        when(head.status()).thenReturn(BriefStatus.COMPLETED);
        when(store.head(any(), any(), any())).thenReturn(Optional.of(head));
        Draft second = generator.generate(job(1));

        assertThat(second.unchanged()).isTrue();
        assertThat(second.unchangedBriefId()).isEqualTo(head.id());
        assertThat(chatModel.prompts()).hasSize(1);
    }

    @Test
    void timeoutIsRetriedThenTheBriefIsWrittenWithoutTheModelPart() {
        evidenceIs(evidence(ResearchState.NEW_RESEARCH, fptEntry()));
        chatModel.reply(prompt -> {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "{}";
        });
        BriefGenerator generator = generator(BriefProperties.NoResearchMode.MINIMAL, chatModel, Duration.ofMillis(100));

        assertThatThrownBy(() -> generator.generate(job(1))).isInstanceOf(BriefGenerator.RetryLater.class).hasMessageContaining("in time");
        Draft last = generator.generate(job(3));

        assertThat(last.status()).isEqualTo(BriefStatus.PARTIAL);
        assertThat(last.aiStatus()).isEqualTo(BriefAiStatus.FAILED);
        assertThat(last.errorCode()).isEqualTo("AI_TIMEOUT");
        assertThat(last.limitationsJson()).contains("AI_FAILED");
        // The deterministic sections are still there, with their citation.
        assertThat(last.contentJson()).contains("TCBS").contains("[S1]");
    }

    @Test
    void permanentProviderFailureGivesAPartialBriefAtOnce() {
        evidenceIs(evidence(ResearchState.NEW_RESEARCH, fptEntry()));
        chatModel.reply(prompt -> {
            throw new NonTransientAiException("model not found");
        });
        Draft draft = generator(BriefProperties.NoResearchMode.MINIMAL, chatModel, Duration.ofSeconds(5)).generate(job(1));

        assertThat(draft.status()).isEqualTo(BriefStatus.PARTIAL);
        assertThat(draft.errorCode()).isEqualTo("AI_PROVIDER_ERROR");
    }

    @Test
    void withoutAChatModelTheBriefIsPartialAndDeterministic() {
        evidenceIs(evidence(ResearchState.NEW_RESEARCH, fptEntry()));
        Draft draft = generator(BriefProperties.NoResearchMode.MINIMAL, null, Duration.ofSeconds(5)).generate(job(1));

        assertThat(draft.status()).isEqualTo(BriefStatus.PARTIAL);
        assertThat(draft.aiStatus()).isEqualTo(BriefAiStatus.NOT_CONFIGURED);
        assertThat(draft.contentJson()).contains("PORTFOLIO_FACT").contains("SOURCE_CLAIM").doesNotContain("AI_INTERPRETATION");
    }

    @Test
    void invalidModelOutputKeepsTheDeterministicBrief() {
        evidenceIs(evidence(ResearchState.NEW_RESEARCH, fptEntry()));
        chatModel.reply("Xin lỗi, tôi không thể trả lời.");
        Draft draft = generator(BriefProperties.NoResearchMode.MINIMAL, chatModel, Duration.ofSeconds(5)).generate(job(1));

        assertThat(draft.status()).isEqualTo(BriefStatus.PARTIAL);
        assertThat(draft.aiStatus()).isEqualTo(BriefAiStatus.COMPLETED);
        assertThat(draft.limitationsJson()).contains("AI_OUTPUT_INVALID");
    }
}
