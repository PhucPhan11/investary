# Phase 5 / Milestone 4 — Automation Center and in-app notifications

Milestone 4 adds a user interface to observe and control the automation built in Milestones 1–3, a persistent
in-app notification system, and AI usage reporting. It does not rebuild Gmail sync, research workflows or
brief generation; it reads their records and calls their services.

Out of scope: outbound e-mail, Telegram or mobile push notifications, trading, real-time market data, new
services or infrastructure.

## Automation Center (`/automation`)

| Tab | Shows | Backed by |
|---|---|---|
| Overview | Gmail connection (account, last success, last failure, next sync, interval, Sync now, connect/disconnect, sync history); metrics for a labelled window (24 h / 7 days / 30 days); system health | `GET /automation/overview`, M1 Gmail endpoints |
| Sources | Brokerage source rules: add, edit, enable/disable, delete, senders, subject filters, auto import, auto PDF download, broad-rule warnings | M1 `/automation/sources` |
| Jobs | Research-processing jobs (filters: status, type, date range, source type, source rule) and brief-generation jobs; detail with stages, attempt history, related email/document; safe retry | `/automation/jobs`, `/briefs/jobs` |
| Settings | Gmail, research processing, daily brief and AI limits | `GET/PUT/DELETE /automation/settings` |
| AI usage | Requests, failures and tokens per day or week, brief generations, analyses, limits and today's use | `GET /automation/usage` |

The notification bell sits in the top bar on every page; `/notifications` lists all notifications.

### Metrics

All counts come from database records for the selected window `[now − period, now)`; "pending jobs" is the
current backlog and is labelled as such.

| Metric | Record |
|---|---|
| Emails imported | `source_messages.imported_at` |
| Emails processed | Email workflows that finished COMPLETED or PARTIAL (`research_workflows.completed_at`) |
| Reports downloaded | Successful download jobs (`download_jobs` SUCCEEDED, `completed_at`) |
| Documents indexed | `knowledge_sources.first_indexed_at` for documents |
| Assessments created | `impact_assessments.created_at` |
| Failed jobs | Automation and brief jobs that ended FAILED in the window |
| Pending jobs | Automation and brief jobs PENDING, RUNNING or WAITING now |

### System health

Each component reports `OPERATIONAL`, `DEGRADED`, `DISABLED` or `UNAVAILABLE` with a reason, the time of its
last activity and the time of the check. The state is computed from records and configuration, never from the
fact that the page loaded; if the backend cannot be reached the UI shows every component as unavailable.

| Component | Disabled / unavailable | Degraded |
|---|---|---|
| Gmail connection | Not configured (UNAVAILABLE); not connected or polling off (DISABLED) | Reconnect required; last run failed; last success older than 3 × the interval |
| Research ingestion | Workflows or the worker disabled (DISABLED); worker heartbeat stale (UNAVAILABLE) | Failed workflow jobs in the last 24 h; a pending job older than `stuck-job-after` |
| Document processing | Download worker disabled; heartbeat stale | Failed downloads in the last 24 h |
| Knowledge indexing | No embedding model (DISABLED) | Failed index jobs in the last 24 h; old pending sources |
| Brief generation | Briefs disabled; worker heartbeat stale | No chat model (deterministic briefs only); last brief job failed |

## Settings

`application.yml` / environment values are the defaults. A value saved in the UI is stored in `app_settings`
and overrides the default until "Reset to default" (ADR 0012). Each section is saved with its version; a
concurrent change returns 409 `SETTINGS_CONFLICT`. Every field is validated on the backend (422 with field
errors).

| Section | Field | Default | Bounds | Applies |
|---|---|---|---|---|
| Gmail | Polling enabled | `true` | — | at once |
| | Polling interval (minutes) | `investary.gmail.sync.interval` (30) | 5–1440 | at once (next tick) |
| | Initial synchronisation window (days) | `initial-lookback-days` (30) | 1–365 | next initial sync |
| | Max messages per synchronisation | `max-messages-per-run` (200) | 10–2000 | next sync |
| Processing | Automatic PDF download (global switch) | `true` | — | at once; a rule must still opt in |
| | Max manual retries per job | `investary.workflow.max-manual-retries` (5) | — | read-only |
| | Max downloads per email / per sync run | 5 / 20 | 0–50 / 0–200 | at once |
| | Max attempts per job | 3 | 1–10 | new jobs and retries |
| | Retry backoff (seconds) | 60 | 1–3600 | at once |
| | Automatic impact assessment | `true` | — | at once |
| | Worker concurrency | 2 | 1–8 | **after restart** |
| Daily brief | Enabled | `true` | — | at once |
| | Scheduled time (HH:mm) | from `schedule.cron` (08:00) | 00:00–23:59 | at once (new series) |
| | Time zone (IANA) | `Asia/Ho_Chi_Minh` | valid `ZoneId` | at once (new series) |
| | Default portfolio | none (every portfolio) | existing portfolio | at once |
| | No-new-research behaviour | `MINIMAL` | `MINIMAL`, `SKIP` | at once |
| | Max AI brief generations per day (shown under AI) | `investary.brief.max-ai-briefs-per-day` (10) | 0–50 | at once |
| AI | Max automatic analyses per day | 20 | 0–200 | at once |
| | Max concurrent chat / embedding requests | 2 / 1 | 1–8 / 1–4 | at once (permits resized) |
| | Daily token budget for automatic calls | none | 1 000–10 000 000 | at once |

Environment-only switches (`*.worker.enabled`, `investary.gmail.sync.scheduler-enabled`,
`investary.workflow.enabled`) are shown read-only. A custom `schedule.cron` that is not a daily time shows as
"custom" until a time is saved.

Changing the brief time or zone changes the brief `configVersion`, so scheduled briefs start a new series and
never collide with earlier ones (Milestone 3 design).

## AI limits (enforced in the backend)

| Limit | Where |
|---|---|
| Automatic analyses per day | `AssessStage` skips with `DAILY_LIMIT_REACHED` |
| AI brief generations per day | `BriefGenerator` produces the deterministic brief with limitation `AI_DAILY_LIMIT` |
| Daily token budget (automatic calls only) | `AssessStage` skips with `TOKEN_BUDGET_REACHED`; briefs fall back to deterministic content |
| Concurrent chat / embedding requests | `AiConcurrencyLimits` (resizable permits) |

Caps are checked before a call and are best effort under concurrency (at most the worker count over).
Interactive assistant questions are not capped by the daily limits; they still take concurrency permits.

## AI usage

`ai_usage_daily` counts provider calls by day (Asia/Ho_Chi_Minh), operation (`CHAT`, `EMBEDDING`), purpose
(`ASSESSMENT`, `BRIEF`, `RESEARCH_ASSISTANT`, `PORTFOLIO_ASSISTANT`, `INDEXING`, `SEARCH`) and model: requests,
failures and provider-reported input/output tokens. Nothing is estimated and no monetary cost is shown, because
no pricing source is configured. Counting starts when Milestone 4 is installed. Brief generations and research
analyses are counted from `briefs` and `impact_assessments`.

## Job history and retry

- Research-processing jobs (`automation_jobs`) now keep an attempt history (`automation_job_attempts`): each
  claim, its result (succeeded, retry scheduled, waiting, failed, lease expired), safe error code and summary,
  and manual retries.
- Retry (`POST /automation/jobs/{id}/retry`) is decided on the backend:
  - only FAILED jobs; not when the source was deleted (`SOURCE_DELETED`);
  - at most `investary.workflow.max-manual-retries` (5) manual retries per job (409 `RETRY_LIMIT_REACHED`);
  - the reset is a single conditional update, so a concurrent second retry gets 409 `JOB_NOT_RETRYABLE`;
  - completed stages are not reset; the job reruns its own stage, and downloads, imports and assessments stay
    idempotent through their M2 keys, so an email is never re-imported and no duplicate document is created.
- The list and detail return `retryable` and `retryBlockedReason`, so the UI only offers allowed retries.
- Brief jobs: `GET /briefs/jobs` lists them; `POST /briefs/jobs/{id}/retry` resets a FAILED brief job (one
  conditional update, so concurrent retries cannot both apply). Its idempotency key is unchanged, so no duplicate
  brief is created. Brief jobs have no manual-retry cap; model calls are bounded by the daily AI brief limit.

## Notifications

Stored in `notifications` (type, priority, title, message, related entity, portfolio, created, read,
`dedup_key` unique). Rules are deterministic: they use stored matches, validated assessment categories,
evidence status, deterministic brief conflicts and job states. No model call decides whether to notify, and
promotional wording in an email is never an input.

| Type | When | Dedup key | Priority |
|---|---|---|---|
| `PORTFOLIO_RESEARCH` | A workflow's matching finds a DIRECT match with a holding | `portfolio-research:{source}:{portfolio}` | NORMAL |
| `NEW_RESEARCH` | A Gmail sync run imported at least one email | `new-research:{run}` | LOW |
| `RESEARCH_RISK` | An automatic assessment item is `POTENTIAL_RISK`, `SUPPORTED` and a direct holding | `risk:{portfolio}:{instrument}:{source}` | HIGH |
| `RESEARCH_CONFLICT` | A brief finds ≥2 brokerages with different explicit ratings for a held symbol | `conflict:{portfolio}:{symbol}:{sources hash}` | NORMAL |
| `DAILY_BRIEF_READY` | A scheduled or manual brief is created (not a regeneration, not unchanged) | `brief-ready:{scope}:{period end}` | NORMAL, LOW without new research |
| `SYNC_FAILED` | 3 consecutive failed Gmail runs, or reconnect required | `sync-failed:{account}:{first failed run}` | HIGH |
| `PROCESSING_FAILED` | A workflow ends FAILED, or a brief job fails permanently | `workflow-failed:{workflow}:{job}` / `brief-job-failed:{job}` | HIGH |

Transient failures that will be retried never notify. A retried job that fails again keeps its key, so it does
not notify twice.

## API (local-only, behind `LocalOnlyRequestFilter`)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/automation/overview?period=24h\|7d\|30d` | Gmail summary, metrics, health |
| GET | `/api/v1/automation/settings` | Sections with value, default, bounds, overridden, applies-after-restart, version |
| PUT | `/api/v1/automation/settings/{section}` | Body `{version, values}`; 422 field errors; 409 `SETTINGS_CONFLICT` |
| DELETE | `/api/v1/automation/settings/{section}/{field}` | Reset to default |
| GET | `/api/v1/automation/jobs?status=&type=&from=&to=&sourceType=&ruleId=&workflowId=&page=&size=` | Adds date and source filters, `sourceTitle`, `retryable` |
| GET | `/api/v1/automation/jobs/{id}` | Job, workflow stages, attempts, related source |
| POST | `/api/v1/automation/jobs/{id}/retry` | 202; 409 `JOB_NOT_RETRYABLE` / `RETRY_LIMIT_REACHED` |
| GET | `/api/v1/automation/usage?period=day\|week&days=` | Usage rows, totals, limits |
| GET | `/api/v1/briefs/jobs?status=&from=&to=&page=&size=` | Brief jobs |
| POST | `/api/v1/briefs/jobs/{id}/retry` | 202; same rules |
| GET | `/api/v1/notifications?unreadOnly=&type=&page=&size=` | Newest first |
| GET | `/api/v1/notifications/unread-count` | `{count}` |
| PATCH | `/api/v1/notifications/{id}/read`, `/{id}/unread` | |
| POST | `/api/v1/notifications/read-all` | |

Source rules (`/automation/sources`) return `warnings` (`PUBLIC_MAIL_DOMAIN`, `MANY_DOMAINS`,
`NO_SUBJECT_FILTER`). Saving a rule for a public mailbox domain such as `gmail.com` requires
`acknowledgeBroadRule: true`; otherwise 422 `BROAD_SOURCE_RULE`. New rules never enable automatic PDF download
unless the request asks for it.

## Database (`V9__automation_center.sql`)

`app_settings`, `notifications`, `automation_job_attempts`, `ai_usage_daily`. Existing tables are unchanged.

## Security

- Every endpoint above is behind `LocalOnlyRequestFilter` (loopback client and host, same-origin writes);
  `/api/v1/notifications/` and `/api/v1/briefs/jobs` were added to it. There is still no authentication: do
  not expose ports 8080 or 4200 beyond the local machine.
- No token, client secret or key reaches the UI; the settings API contains no credential.
- Errors and attempt summaries go through `ErrorSummaries.safe` (no URLs, stack traces or secrets).
- Notification text is built from stored titles, symbols and portfolio names, never from model output, and is
  not logged.

## Known limitations

- Usage counters start at the upgrade; there is no cost estimate.
- Daily caps are best effort under concurrency (bounded by the number of workers).
- Worker concurrency changes need a restart.
- Brief job retry history keeps only the latest error (brief jobs have no attempts table).
- Jobs created before the upgrade have no attempt history.
- The overview counts are read with read-only SQL on the owning modules' tables (as the M2 reconcile does).
- `RESEARCH_RISK` counts manual assessments as tokens used, but notifies only for automatic ones.
- Notifications are not pruned; read ones stay until the database is cleaned up.
- Conflict notifications only see explicit rating labels (the M3 detector).
- `RESEARCH_RISK` needs a chat model: the risk category exists only after a validated AI interpretation.
