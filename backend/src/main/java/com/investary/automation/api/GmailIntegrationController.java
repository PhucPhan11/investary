package com.investary.automation.api;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import com.investary.automation.GmailProperties;
import com.investary.automation.application.GmailConnectionService;
import com.investary.automation.application.GmailConnectionService.PendingAuthorization;
import com.investary.automation.application.GmailSyncService;
import com.investary.automation.application.GmailSyncService.GmailStatusView;
import com.investary.automation.application.GmailSyncService.SyncRunView;
import com.investary.automation.domain.SyncTrigger;
import com.investary.common.web.PageResponse;

/**
 * Gmail connection and sync. Local-only (see {@link LocalOnlyRequestFilter}); never returns tokens.
 */
@RestController
@RequestMapping(GmailIntegrationController.BASE)
public class GmailIntegrationController {

    static final String BASE = "/api/v1/integrations/gmail";
    static final String CALLBACK = BASE + "/callback";
    static final String BINDING_COOKIE = "investary_gmail_oauth";

    private final GmailConnectionService connection;
    private final GmailSyncService syncs;
    private final GmailProperties properties;

    public GmailIntegrationController(GmailConnectionService connection, GmailSyncService syncs, GmailProperties properties) {
        this.connection = connection;
        this.syncs = syncs;
        this.properties = properties;
    }

    @GetMapping("/status")
    public GmailStatusView status() {
        return syncs.status();
    }

    /** Starts the authorization; the browser then navigates to {@code authorizationUrl}. */
    @PostMapping("/connect")
    public ResponseEntity<ConnectResponse> connect() {
        PendingAuthorization pending = connection.connect();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, bindingCookie(pending.browserBinding(), properties.stateTtl()).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ConnectResponse(pending.authorizationUrl()));
    }

    /** Google's redirect. Always answers with a redirect to the UI carrying a fixed result code. */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error,
                                         @CookieValue(name = BINDING_COOKIE, required = false) String binding) {
        Optional<String> failure = connection.complete(code, state, error, binding);
        UriComponentsBuilder target = UriComponentsBuilder.fromUriString(properties.uiRedirectUrl());
        if (failure.isEmpty()) {
            target.queryParam("gmail", "connected");
        } else {
            target.queryParam("gmail", "error").queryParam("reason", failure.get());
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(target.build().toUriString()))
                .header(HttpHeaders.SET_COOKIE, bindingCookie("", Duration.ZERO).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    @PostMapping("/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect() {
        connection.disconnect();
    }

    @PostMapping("/sync")
    public ResponseEntity<SyncStarted> sync() {
        UUID runId = syncs.requestSync(SyncTrigger.MANUAL);
        return ResponseEntity.accepted()
                .location(URI.create(BASE + "/sync-history/" + runId))
                .body(new SyncStarted(runId));
    }

    @GetMapping("/sync-history")
    public PageResponse<SyncRunView> history(@RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "20") int size) {
        return syncs.history(page, size);
    }

    @GetMapping("/sync-history/{runId}")
    public SyncRunView run(@PathVariable UUID runId) {
        return syncs.run(runId);
    }

    private static ResponseCookie bindingCookie(String value, Duration maxAge) {
        return ResponseCookie.from(BINDING_COOKIE, value)
                .httpOnly(true)
                .sameSite("Lax")
                .path(CALLBACK)
                .maxAge(maxAge)
                .build();
    }

    public record ConnectResponse(String authorizationUrl) {
    }

    public record SyncStarted(UUID runId) {
    }
}
