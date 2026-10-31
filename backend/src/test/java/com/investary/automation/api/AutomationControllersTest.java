package com.investary.automation.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.investary.automation.GmailProperties;
import com.investary.automation.application.AutomationExceptions.DuplicateSourceRuleException;
import com.investary.automation.application.AutomationExceptions.GmailNotConfiguredException;
import com.investary.automation.application.AutomationExceptions.GmailNotConnectedException;
import com.investary.automation.application.AutomationExceptions.InvalidSourceRuleException;
import com.investary.automation.application.AutomationExceptions.SourceRuleNotFoundException;
import com.investary.automation.application.AutomationExceptions.SyncInProgressException;
import com.investary.automation.application.GmailConnectionService;
import com.investary.automation.application.GmailConnectionService.PendingAuthorization;
import com.investary.automation.application.GmailSyncService;
import com.investary.automation.application.GmailSyncService.GmailStatusView;
import com.investary.automation.application.SourceRuleService;
import com.investary.automation.application.SourceRuleService.SourceRuleView;
import com.investary.automation.domain.SyncTrigger;

@WebMvcTest({GmailIntegrationController.class, SourceRuleController.class})
@EnableConfigurationProperties(GmailProperties.class)
class AutomationControllersTest {

    static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Autowired MockMvc mvc;
    @MockitoBean GmailConnectionService connection;
    @MockitoBean GmailSyncService syncs;
    @MockitoBean SourceRuleService rules;

    @Test
    void statusNeverContainsTokens() throws Exception {
        when(syncs.status()).thenReturn(new GmailStatusView(false, null, false, null, 0, true, 30, 30));
        mvc.perform(get("/api/v1/integrations/gmail/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.account").doesNotExist());
    }

    @Test
    void connectReturnsTheGoogleUrlAndSetsAnHttpOnlyBindingCookie() throws Exception {
        when(connection.connect()).thenReturn(new PendingAuthorization("https://accounts.example/auth?state=s", "binding-value"));
        mvc.perform(post("/api/v1/integrations/gmail/connect"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorizationUrl").value("https://accounts.example/auth?state=s"))
                .andExpect(jsonPath("$.browserBinding").doesNotExist())
                .andExpect(cookie().value("investary_gmail_oauth", "binding-value"))
                .andExpect(cookie().httpOnly("investary_gmail_oauth", true))
                .andExpect(cookie().path("investary_gmail_oauth", "/api/v1/integrations/gmail/callback"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Lax")));

        org.mockito.Mockito.doThrow(new GmailNotConfiguredException()).when(connection).connect();
        mvc.perform(post("/api/v1/integrations/gmail/connect"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GMAIL_NOT_CONFIGURED"));
    }

    @Test
    void callbackRedirectsToTheUiWithAFixedCode() throws Exception {
        when(connection.complete("c", "s", null, "b")).thenReturn(Optional.empty());
        mvc.perform(get("/api/v1/integrations/gmail/callback").param("code", "c").param("state", "s")
                        .cookie(new jakarta.servlet.http.Cookie("investary_gmail_oauth", "b")))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "http://localhost:4200/automation?gmail=connected"))
                .andExpect(cookie().maxAge("investary_gmail_oauth", 0));

        when(connection.complete(null, "s", "access_denied", null)).thenReturn(Optional.of("ACCESS_DENIED"));
        mvc.perform(get("/api/v1/integrations/gmail/callback").param("state", "s").param("error", "access_denied"))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "http://localhost:4200/automation?gmail=error&reason=ACCESS_DENIED"));
    }

    @Test
    void syncIsAcceptedOrConflicts() throws Exception {
        when(syncs.requestSync(SyncTrigger.MANUAL)).thenReturn(ID);
        mvc.perform(post("/api/v1/integrations/gmail/sync"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runId").value(ID.toString()))
                .andExpect(header().string("Location", "/api/v1/integrations/gmail/sync-history/" + ID));

        when(syncs.requestSync(SyncTrigger.MANUAL)).thenThrow(new SyncInProgressException());
        mvc.perform(post("/api/v1/integrations/gmail/sync"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GMAIL_SYNC_IN_PROGRESS"));

        org.mockito.Mockito.doThrow(new GmailNotConnectedException("Connect a Gmail account first")).when(syncs).requestSync(SyncTrigger.MANUAL);
        mvc.perform(post("/api/v1/integrations/gmail/sync"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("GMAIL_NOT_CONNECTED"));
    }

    @Test
    void disconnectAnswersNoContent() throws Exception {
        mvc.perform(post("/api/v1/integrations/gmail/disconnect")).andExpect(status().isNoContent());
        verify(connection).disconnect();
    }

    @Test
    void createsAndValidatesSourceRules() throws Exception {
        SourceRuleView view = new SourceRuleView(ID, "TCBS", List.of(), List.of("tcbs.com.vn"), List.of(), null, true, true, false,
                Instant.EPOCH, Instant.EPOCH);
        when(rules.create(any())).thenReturn(view);
        mvc.perform(post("/api/v1/automation/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TCBS\",\"senderDomains\":[\"tcbs.com.vn\"]}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/automation/sources/" + ID))
                .andExpect(jsonPath("$.autoPdfDownload").value(false));

        mvc.perform(post("/api/v1/automation/sources").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.name").exists());

        org.mockito.Mockito.doThrow(new InvalidSourceRuleException(Map.of("gmailQuery", "must not search spam"))).when(rules).create(any());
        mvc.perform(post("/api/v1/automation/sources").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"X\",\"senderDomains\":[\"x.vn\"],\"gmailQuery\":\"in:spam\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.gmailQuery").value("must not search spam"));

        when(rules.update(eq(ID), any())).thenThrow(new DuplicateSourceRuleException("TCBS"));
        mvc.perform(put("/api/v1/automation/sources/{id}", ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"TCBS\",\"senderDomains\":[\"tcbs.com.vn\"]}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_SOURCE_RULE"));
    }

    @Test
    void unknownRuleIsNotFound() throws Exception {
        org.mockito.Mockito.doThrow(new SourceRuleNotFoundException(ID)).when(rules).delete(ID);
        mvc.perform(delete("/api/v1/automation/sources/{id}", ID))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SOURCE_RULE_NOT_FOUND"));
    }

    @Test
    void remoteClientsAreRefusedBeforeTheController() throws Exception {
        mvc.perform(get("/api/v1/automation/sources").with(request -> {
                    request.setRemoteAddr("203.0.113.9");
                    return request;
                }))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("LOCAL_ONLY"));
    }
}
