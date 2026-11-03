package com.investary.automation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.investary.automation.application.AutomationExceptions.BroadSourceRuleException;
import com.investary.automation.application.SourceRuleService.SourceRuleCommand;
import com.investary.automation.application.SourceRuleService.SourceRuleView;
import com.investary.automation.domain.EmailSourceRule;
import com.investary.automation.persistence.EmailSourceRuleRepository;

class SourceRuleWarningsTest {

    private final EmailSourceRuleRepository repository = mock(EmailSourceRuleRepository.class);
    private final SourceRuleService service = new SourceRuleService(repository, Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void warnsAboutRulesThatCouldImportMoreThanResearch() {
        assertThat(SourceRuleWarnings.of(List.of(), List.of("gmail.com"), List.of(), null))
                .containsExactly(SourceRuleWarnings.PUBLIC_MAIL_DOMAIN, SourceRuleWarnings.NO_SUBJECT_FILTER);
        List<String> many = IntStream.range(0, 11).mapToObj(i -> "broker" + i + ".com.vn").toList();
        assertThat(SourceRuleWarnings.of(List.of(), many, List.of("bản tin"), null)).containsExactly(SourceRuleWarnings.MANY_DOMAINS);
        assertThat(SourceRuleWarnings.of(List.of("research@tcbs.com.vn"), List.of(), List.of(), null)).isEmpty();
        assertThat(SourceRuleWarnings.of(List.of(), List.of("tcbs.com.vn"), List.of(), "subject:research")).isEmpty();
    }

    @Test
    void aPublicMailboxDomainMustBeAcknowledgedAndAutoDownloadStaysOff() {
        when(repository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.create(command(null)))
                .isInstanceOfSatisfying(BroadSourceRuleException.class,
                        e -> assertThat(e.getProperties().get("domains")).isEqualTo(List.of("gmail.com")));

        SourceRuleView view = service.create(command(true));
        assertThat(view.warnings()).contains(SourceRuleWarnings.PUBLIC_MAIL_DOMAIN);
        assertThat(view.autoPdfDownload()).isFalse();
    }

    @Test
    void anAcceptedPublicDomainNeedNotBeAcknowledgedAgainOnEdit() {
        EmailSourceRule existing = new EmailSourceRule(Instant.EPOCH);
        existing.update("Analyst", List.of(), List.of("gmail.com"), List.of(), null, true, true, false, Instant.EPOCH);
        when(repository.findById(any())).thenReturn(Optional.of(existing));
        when(repository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        SourceRuleView view = service.update(UUID.randomUUID(), new SourceRuleCommand("Analyst", List.of(), List.of("gmail.com"),
                List.of(), null, false, null, null, null));
        assertThat(view.enabled()).isFalse();
    }

    private static SourceRuleCommand command(Boolean acknowledge) {
        return new SourceRuleCommand("Analyst", List.of(), List.of("gmail.com"), List.of(), null, null, null, null, acknowledge);
    }
}
