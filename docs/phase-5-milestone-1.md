# Phase 5 / Milestone 1 — Gmail integration and synchronization

Status: implemented on branch `phase-4-portfolio-intelligence` (not committed). Date: 2026-10-31.

## Goal

Stop exporting brokerage emails by hand. Investary connects one Gmail account **read-only**, keeps
user-defined brokerage source rules, and pulls matching messages into the **existing** Phase 2 email
import as their original RFC 822 bytes: an initial lookback first, then incremental updates through
Gmail's history API, on a schedule or on demand.

Out of scope (later milestones): automated PDF download orchestration, automatic indexing or impact
runs triggered by sync, briefs, notifications, an Automation Center, AI agents, push notifications
(Pub/Sub), message brokers and new services.

## Decisions

| Topic | Decision |
|---|---|
| Module | New `com.investary.automation` (`api`, `application`, `domain`, `persistence`, `google`, `security`). It depends only on `email` (application) and `common`; nothing imports it. Enforced by `ArchitectureTest` |
| Import path | `EmailImportService.importRaw(bytes, SourceType.GMAIL, filename)`: the Phase 2 pipeline (SHA-256 dedup → parse → store original → persist → PDF attachments). No second parser, no EML reconstruction |
| OAuth | Authorization code + PKCE (`S256`), `gmail.readonly` only, `access_type=offline`, `prompt=consent`, one-time `state` bound to the browser by an HttpOnly cookie. See [ADR 0009](adr/0009-gmail-oauth-and-sync.md) |
| Tokens | Refresh token encrypted at rest (AES-256-GCM, key from `INVESTARY_GMAIL_TOKEN_KEY`); access tokens only in memory; nothing token-like ever reaches the browser or the logs |
| Account identity | `users.getProfile` → `emailAddress` (no extra `openid`/`email` scope) |
| Sender matching | The addr-spec of the single `From` mailbox (never the display name); rejected when Gmail's own `Authentication-Results` says `dmarc=fail` |
| Dedup | Gmail message id per account (`gmail_messages` unique key) **and** the Phase 2 SHA-256 of the raw bytes |
| Cursor | `history_id` advances only after every message up to that point is durably handled |
| Concurrency | One sync per account via a database lease with a token (`sync_lock_token`, `sync_lock_until`) |
| Scheduling | `@Scheduled` fixed delay (default 30 min) like the download worker; a single-thread executor runs syncs |
| Boundary | `/api/v1/integrations/**` and `/api/v1/automation/**` accept only loopback clients with a loopback `Host` and (for writes) a loopback `Origin` |

## Schema — `V6__gmail_sync.sql`

- `source_messages.source_type` now allows `GMAIL` (check constraint replaced; no data change).
- `gmail_accounts`: email address (unique, case-insensitive), status (`CONNECTED`,
  `RECONNECT_REQUIRED`, `DISCONNECTED`), encrypted refresh token + key fingerprint, granted scopes,
  connection timestamps, sync cursor (`history_id`), `pending_history_id`, initial-sync marker, last
  sync start/success/error, and the sync lease (`sync_lock_token`, `sync_lock_until`). The schema
  allows several accounts; the service keeps one active account.
- `email_source_rules`: name (unique, case-insensitive), `sender_addresses text[]`, `sender_domains text[]`
  (at least one sender), `subject_keywords text[]`, optional `gmail_query`, `enabled`, `auto_import`
  (default true), `auto_pdf_download` (default false), timestamps.
- `gmail_messages`: per account and Gmail message id (unique): thread id, history id, internal date,
  outcome (`IMPORTED`, `DUPLICATE`, `NOT_IMPORTED`, `REJECTED`), matching rule, imported
  `source_messages` row (`ON DELETE SET NULL`), sync run, error code.
- `gmail_sync_runs`: trigger (`MANUAL`, `SCHEDULED`), mode (`INITIAL`, `INCREMENTAL`, `RECOVERY`),
  status (`RUNNING`, `SUCCEEDED`, `PARTIAL`, `FAILED`), counters, history ids, error.

## OAuth flow

```
Angular ─POST /integrations/gmail/connect─▶ backend: state (256 bit), PKCE verifier, cookie binding
        ◀─ {authorizationUrl} + Set-Cookie investary_gmail_oauth (HttpOnly, SameSite=Lax, 10 min)
browser ─▶ accounts.google.com (consent: gmail.readonly) ─▶ GET /integrations/gmail/callback?code&state
backend: state one-time + unexpired + cookie matches → token exchange (code_verifier, client secret)
       → scope includes gmail.readonly? refresh_token present? → users.getProfile → encrypt + upsert
       ─303─▶ http://localhost:4200/automation?gmail=connected  (or ?gmail=error&reason=<CODE>)
```

- Reasons on the redirect are fixed codes (`ACCESS_DENIED`, `INVALID_STATE`, `TOKEN_EXCHANGE_FAILED`,
  `SCOPE_NOT_GRANTED`, `NO_REFRESH_TOKEN`, `ACCOUNT_LIMIT`, `NOT_CONFIGURED`, `GOOGLE_ERROR`); Google's
  error text is never reflected.
- Pending states live in memory (bounded, 10-minute TTL, single use). A backend restart during consent
  just means clicking Connect again.
- **Refresh:** on demand, server-side, and once more after a `401` from Gmail. `invalid_grant` (revoked,
  expired, password change, Testing-mode expiry) clears the stored token and sets
  `RECONNECT_REQUIRED`; Reconnect runs the same flow and keeps the cursor.
- **Disconnect:** revoke at Google (`POST /revoke`, form `token=`), then delete the ciphertext, the cached
  access token and the cursor. Imported emails stay.

## Sync

```
POST /sync or scheduler ─▶ lease on gmail_accounts (token) ─▶ GmailSyncRunner
  no cursor / initial incomplete ─▶ INITIAL:  profile.historyId → pending; messages.list per rule
                                             q = from:(…) [(gmail query)] after:<now − lookback>
  cursor ─▶ INCREMENTAL: history.list(startHistoryId, messageAdded), page by page
         └ 404 ─▶ RECOVERY: profile.historyId; messages.list after:max(lastSuccess − 1d, now − recovery max)
  per message: known id? skip │ metadata (From, Subject, Authentication-Results, size) → rules
     no rule → skipped (not stored) │ autoImport off → NOT_IMPORTED │ too large / dmarc=fail → REJECTED
     raw → base64url decode → EmailImportService.importRaw → IMPORTED │ checksum duplicate → DUPLICATE
  checkpoint: after each fully handled history record (token-guarded); final historyId at the end
```

- **Limits:** at most `max-messages-per-run` (default 200) *matching* messages are handled per run; the run
  is then `PARTIAL` and continues next time (known ids are skipped without a Gmail fetch). A second Gmail
  account is refused at the callback (`?gmail=error&reason=ACCOUNT_LIMIT`, and the new grant is revoked).
- **No enabled source rule:** nothing is listed and the initial sync stays pending, so adding the first rule
  later still triggers the initial lookback.
- **Failures:** 429, 5xx and `403 rateLimitExceeded/userRateLimitExceeded` retry with exponential
  backoff (honouring `Retry-After`, bounded). When retries run out the run fails and the cursor stays
  where it was, so nothing is skipped. A crash between import and mapping is healed by the checksum
  (`DUPLICATE` mapping to the existing email). Runs left `RUNNING` by a crash are closed as
  `INTERRUPTED` when the lease has expired.
- A deleted email keeps its Gmail mapping and is **not** re-imported.
- `autoPdfDownload` is stored only. Linked reports are still downloaded on request, as in Phase 2.

## API

| Method | Path | Result |
|---|---|---|
| `GET` | `/api/v1/integrations/gmail/status` | configuration flag, account, status, last run, scheduler |
| `POST` | `/api/v1/integrations/gmail/connect` | `{authorizationUrl}` + HttpOnly binding cookie · `503 GMAIL_NOT_CONFIGURED` |
| `GET` | `/api/v1/integrations/gmail/callback` | `303` to the UI |
| `POST` | `/api/v1/integrations/gmail/disconnect` | `204` |
| `POST` | `/api/v1/integrations/gmail/sync` | `202 {runId}` · `409 GMAIL_SYNC_IN_PROGRESS` · `409 GMAIL_NOT_CONNECTED` |
| `GET` | `/api/v1/integrations/gmail/sync-history?page&size` | page of runs |
| `GET` | `/api/v1/integrations/gmail/sync-history/{runId}` | one run · `404 SYNC_RUN_NOT_FOUND` |
| `GET`/`POST` | `/api/v1/automation/sources` | list / create (`201`) |
| `GET`/`PUT`/`DELETE` | `/api/v1/automation/sources/{id}` | rule · `404 SOURCE_RULE_NOT_FOUND` · `409 DUPLICATE_SOURCE_RULE` |

Requests from a non-loopback address, with a non-loopback `Host`, or (for writes) with a foreign
`Origin` or `Sec-Fetch-Site: cross-site` get `403 LOCAL_ONLY`.

## Google Cloud setup (manual, once)

1. Open the [Google Cloud console](https://console.cloud.google.com/), create a project (e.g. "Investary local").
2. *APIs & Services → Library*: enable the **Gmail API**.
3. *Google Auth Platform → Branding / Audience*: user type **External**, publishing status **Testing**,
   and add your Gmail address under **Test users**.
4. *Data access*: add the scope `https://www.googleapis.com/auth/gmail.readonly` (a restricted scope;
   fine for a personal app in Testing).
5. *Clients → Create client*: type **Web application**, authorized redirect URI exactly
   `http://localhost:8080/api/v1/integrations/gmail/callback`. Copy the client id and secret.
6. Generate a token encryption key (32 random bytes, base64):
   `node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"`
7. Put these into `infrastructure/.env` (never commit it):
   `INVESTARY_GMAIL_CLIENT_ID`, `INVESTARY_GMAIL_CLIENT_SECRET`, `INVESTARY_GMAIL_TOKEN_KEY`.
8. Restart the backend, open **http://localhost:4200/automation** (use `localhost`, not `127.0.0.1`, so
   the binding cookie reaches the callback), add source rules, then **Connect Gmail**.

Changing `INVESTARY_GMAIL_TOKEN_KEY` makes the stored token unreadable: the account switches to
`RECONNECT_REQUIRED`.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `INVESTARY_GMAIL_CLIENT_ID` / `INVESTARY_GMAIL_CLIENT_SECRET` | — | OAuth client (Web application) |
| `INVESTARY_GMAIL_TOKEN_KEY` | — | Base64 AES-256 key for the stored refresh token |
| `INVESTARY_GMAIL_REDIRECT_URI` | `http://localhost:8080/api/v1/integrations/gmail/callback` | Must match the client exactly |
| `INVESTARY_GMAIL_UI_REDIRECT_URL` | `http://localhost:4200/automation` | Where the callback sends the browser |
| `INVESTARY_GMAIL_SYNC_SCHEDULER_ENABLED` | `true` | Periodic sync |
| `INVESTARY_GMAIL_SYNC_INTERVAL` | `30m` | Delay between scheduled syncs |
| `INVESTARY_GMAIL_INITIAL_LOOKBACK_DAYS` | `30` | Initial sync window |
| `INVESTARY_GMAIL_MAX_MESSAGES_PER_RUN` | `200` | Messages fetched per run |
| `INVESTARY_GMAIL_RECOVERY_MAX_LOOKBACK_DAYS` | `30` | Upper bound of the recovery window after an expired cursor |

Endpoint URLs, timeouts, retries, lease and state TTL are under `investary.gmail.*` in `application.yml`.

## Known limitations

- **No authentication.** The loopback boundary keeps other machines out, but any local process or user
  on this machine can call the endpoints. Do not expose port 8080.
- **Testing-mode refresh tokens expire after 7 days** (Google policy for apps in Testing with
  restricted scopes). Expect `RECONNECT_REQUIRED` weekly unless the app is verified.
- Polling only (no Pub/Sub push); one connected account; history records can expire (≈ a week or
  less), in which case a bounded recovery window is used — mail older than that window is not
  backfilled automatically.
- Gmail's `q` is only a prefilter; local rules decide. Messages that match no rule are not recorded, so
  a rule added later only sees new mail and mail inside the next initial/recovery window (reconnect to
  re-run an initial sync).
- `NOT_IMPORTED` messages (rule with auto-import off) are recorded but cannot be imported from the UI yet.
- DMARC is checked only from Gmail's own `Authentication-Results`; a sender without DMARC is matched on
  the address alone.
- `autoPdfDownload` is stored but not acted on (Milestone 2).

## Implementation map

| Area | Files (under `backend/src/main/java/com/investary/automation`) |
|---|---|
| Config | `GmailProperties` (`investary.gmail.*`) |
| OAuth | `security/OAuthStateStore`, `google/GoogleOAuthClient`, `application/GmailConnectionService`, `api/GmailIntegrationController` |
| Tokens | `security/TokenCipher` (AES-256-GCM), `application/GmailAccessTokens` (in-memory access tokens, refresh, `RECONNECT_REQUIRED`) |
| Gmail API | `google/GoogleHttp`, `google/GmailApiClient` (retries, 401 refresh, 404 classification) |
| Rules | `domain/EmailSourceRule`, `application/SourceRuleService`, `GmailQueryValidator`, `SenderVerifier`, `RuleMatcher`, `api/SourceRuleController` |
| Sync | `application/GmailSyncService` (lock, executor, status), `GmailSyncRunner` (initial / incremental / recovery), `GmailSyncScheduler` |
| Persistence | `persistence/GmailAccountStore`, `GmailSyncRunStore`, `GmailMessageStore`, `EmailSourceRuleRepository` |
| Boundary | `api/LocalOnlyRequestFilter` |
| Phase 2 change | `email/application/EmailImportService.importRaw` (size check + `SourceType`), `email/domain/SourceType.GMAIL` |
| Frontend | `features/automation/gmail-sync.*`, `core/api/automation-api.service.ts`, `core/models/automation.model.ts`, route `/automation`, nav "Gmail sync" |

## Test results (mocked Google; 2026-10-31)

All Google traffic in tests goes to WireMock; no real Google account, credential or email was used.

- `cd backend && ./mvnw verify` → **BUILD SUCCESS, 369 tests, 0 failures, 0 errors, 0 skipped** (Docker running,
  so the Testcontainers suites executed). New suites: `TokenCipherTest`, `OAuthStateStoreTest`,
  `SenderVerifierTest`, `RuleMatcherTest`, `GmailQueryValidatorTest`, `SourceRuleServiceTest`, `GoogleClientsTest`,
  `LocalOnlyRequestFilterTest`, `AutomationControllersTest`, `GmailSyncIntegrationTest` (14 scenarios: OAuth
  state/cookie/replay/denied, missing scope, second account, initial + incremental sync with pagination and token
  refresh, sender spoofing and `dmarc=fail`, manual-import duplicate, auto-import off, oversized/invalid
  messages, import limit, expired cursor with bounded recovery, transient 503 with cursor kept and resume,
  concurrent and interrupted runs, revoked grant and reconnect, disconnect with revocation).
- `cd frontend && npm test -- --watch=false` → 17 files, 56 tests passed; `npm run build` → success.
- `node scripts/check-harness.mjs` → passed (V6 checksum recorded).

**Not verified:** a live Google consent, real Gmail API responses and real brokerage messages. Use the
checklist below.

## Live verification checklist (real Gmail; not run by the automated tests)

1. Google Cloud setup done; backend log shows no Gmail configuration warning; `/automation` shows
   *Not connected* with **Connect Gmail** enabled.
2. Create a rule (e.g. TCBS, domain `tcbs.com.vn`). Connect → Google consent shows only "Read your
   email messages and settings" → back on `/automation` with *Connected* and your address.
3. In the database, `gmail_accounts.refresh_token_ciphertext` is binary, not a readable token; the
   browser's network tab never shows a token.
4. **Sync now** → a run appears (`INITIAL`), then `SUCCEEDED`; imported emails appear under *Email
   research* with the right sender, and `GET /api/v1/emails/{id}/original` equals Gmail's *Download message*.
5. Sync again → nothing new imported (`listed` > 0, `imported` 0).
6. Send yourself a matching test mail (or wait for a newsletter) → next sync is `INCREMENTAL` and imports it.
7. A non-matching mail and a spoofed display name are not imported.
8. Revoke access at https://myaccount.google.com/permissions → next sync fails with
   `RECONNECT_REQUIRED`; Reconnect works.
9. Disconnect → status *Not connected*; Investary disappears from the Google permissions page.
10. From another machine on the LAN (if the port were reachable), `/api/v1/integrations/gmail/status`
    answers `403 LOCAL_ONLY`.
