# ADR 0009 — Gmail OAuth and synchronization

- Status: accepted
- Date: 2026-10-31

## Context

Brokerage research arrives by email. Exporting `.eml` files by hand (Phase 2) does not scale, so Phase 5
Milestone 1 reads the user's Gmail. That means holding a long-lived Google credential in an application
that has **no authentication** and runs on the user's machine, and importing third-party mail
automatically. Google offers a REST API with OAuth 2.0, incremental history, and push (Pub/Sub).

## Decision

1. **OAuth 2.0 authorization code flow for a web server application, with PKCE (`S256`).**
   - Scope: `gmail.readonly` only. The account address comes from `users.getProfile`, so there is no
     `openid`/`email` scope.
   - `access_type=offline` and `prompt=consent` so a refresh token is issued (also on reconnect).
   - A 256-bit one-time `state` with a 10-minute TTL, bound to the starting browser by an HttpOnly
     `SameSite=Lax` cookie (login-CSRF protection). Pending states live in memory.
   - The callback checks the granted `scope` and the presence of a refresh token, and redirects to the
     UI with a fixed result code; Google's error text is never reflected.
2. **Tokens never leave the backend.** The refresh token is stored AES-256-GCM encrypted (random IV,
   account id as AAD, key fingerprint stored). The key comes from `INVESTARY_GMAIL_TOKEN_KEY`. Access
   tokens are kept only in memory. `invalid_grant` or an unreadable token puts the account into
   `RECONNECT_REQUIRED`. Disconnect revokes the grant at Google and deletes the token.
3. **Local-only boundary.** `/api/v1/integrations/**` and `/api/v1/automation/**` require a loopback
   client address and `Host`, and a loopback `Origin` (no `Sec-Fetch-Site: cross-site`) for writes.
   This is defence in depth for a single-user local app, not authentication.
4. **Import through Phase 2.** Messages are fetched with `format=raw` and the decoded bytes go through
   `EmailImportService.importRaw` (`source_type = GMAIL`): same parser, same original-file storage,
   same SHA-256 dedup. Investary never rebuilds an EML from parsed fields.
5. **User-defined source rules decide what is imported.** The match is on the addr-spec of the single
   `From` mailbox (display names ignored), with domains matching sub-domains. A message is rejected if
   Gmail's own `Authentication-Results` reports `dmarc=fail`. Gmail's `q` is only a prefilter.
6. **Polling with history.** Initial sync over a lookback window, then `history.list` from a stored
   cursor. The cursor advances only past fully handled history records. On 404 (expired cursor), a
   recovery listing runs over a bounded window (since the last success − 1 day, capped by config). Gmail
   message ids are recorded per account (`gmail_messages`), so a message is handled once even if its
   email is later deleted.
7. **One sync per account** through a database lease with a token on `gmail_accounts`. Manual and
   scheduled syncs share it, and every cursor write is token-guarded.

## Consequences

- No Google client library and no new infrastructure: a few REST calls through Spring's `RestClient`,
  with endpoint URLs in config so WireMock can stand in for Google in tests.
- Polling every 30 minutes adds latency compared with Pub/Sub push, but needs no public endpoint, which
  a local app does not have.
- Apps in the Google *Testing* publishing status get refresh tokens that expire after 7 days, so the
  user reconnects weekly unless the app is verified. The UI makes this visible (`RECONNECT_REQUIRED`).
- Messages that match no rule are not recorded. A rule added later sees new mail; older mail is only
  picked up by a new initial sync (after reconnecting) or a recovery window.
- Changing the token key forces a reconnect; that is intentional.
- Anyone who can reach the backend on the loopback interface can still trigger syncs or disconnect.
  Real authentication remains a later phase.
