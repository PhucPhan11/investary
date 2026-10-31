# Phase 5 / Milestone 2 — Automated research processing

Milestone 2 connects the existing capabilities into one persistent workflow per source:

```
Gmail sync / EML upload / library upload
  → EML import (Phase 2, unchanged)          → links classified, research items (Phase 2, unchanged)
  → automatic PDF download (Phase 2 downloader, only for approved Gmail rules)
  → knowledge indexing (Phase 3 queue and processor)
  → deterministic portfolio matching (new, intelligence module, no AI)
  → impact assessment (Phase 4 service, model optional, daily cap)
```

It only orchestrates. Parsing, link classification, downloading, indexing, matching and assessment remain
in their own modules; the `automation` module calls their `application` services and keeps the workflow
state. Out of scope (later milestones): daily briefs, notifications, the full Automation Center UI,
autonomous agents and trading.

## Workflow model

One row in `research_workflows` per source: an imported email (`source_message_id`) or a document
uploaded to the library (`document_id`); attachments and downloaded reports belong to their email's
workflow. `workflow_stages` holds one row per stage; `automation_jobs` is the job queue that drives them.

| Stage | Job | What it does | Mandatory |
|---|---|---|---|
| `EMAIL_IMPORTED` | PREPARE | Reads the import result (done synchronously by the Phase 2 import) | email |
| `EMAIL_PARSED` | PREPARE | `PARSED` → COMPLETED, `PARTIAL` → PARTIAL, `FAILED` → FAILED | email |
| `LINKS_CLASSIFIED` | PREPARE | Counts links, research/PDF links, ignored links, research items; SKIPPED `NO_LINKS` | |
| `DOCUMENTS_PROCESSED` | DOWNLOADS | Automatic downloads (policy below), waits for the download worker | |
| `KNOWLEDGE_INDEXED` | INDEX | Requests indexing of the email and its documents, waits for the knowledge worker | yes |
| `PORTFOLIO_MATCHED` | MATCH | Deterministic ticker matching against open holdings | |
| `IMPACT_ASSESSED` | ASSESS (one per portfolio) | Phase 4 assessment for matched portfolios | |
| `COMPLETED` | — | Mirrors the final workflow status | |

For a library document, the email stages and `DOCUMENTS_PROCESSED` are SKIPPED (`NOT_APPLICABLE`) and
only `KNOWLEDGE_INDEXED` is mandatory.

### Statuses and transitions

Stage and workflow statuses: `PENDING`, `RUNNING`, `COMPLETED`, `PARTIAL`, `FAILED`, `SKIPPED`.
SKIPPED means "nothing eligible" or "option disabled" and is never a failure (reasons such as
`NO_ELIGIBLE_LINKS`, `AUTO_DOWNLOAD_DISABLED`, `EMBEDDINGS_NOT_CONFIGURED`, `NO_RELEVANT_HOLDINGS`,
`DAILY_LIMIT_REACHED`, `NOT_REACHED`).

```
start ──▶ RUNNING ──(no active job)──▶ COMPLETED | PARTIAL | FAILED
              ▲                                   │
              └──── retry / process / report downloaded (re-open) ◀┘
```

When no job of a workflow is active, `WorkflowStatusPolicy` decides:

- **FAILED** if a mandatory stage failed (the email cannot be imported/parsed/indexed).
- **PARTIAL** if an optional stage failed or finished partially (a report could not be downloaded, an
  assessment failed).
- **COMPLETED** if every stage completed or was skipped.

Stages the chain never reached (after a failure) become SKIPPED `NOT_REACHED`. A workflow is never
COMPLETED while a mandatory stage is failed.

Jobs: `PENDING → RUNNING → SUCCEEDED | FAILED`, plus `WAITING` for a stage waiting on another worker
(downloads, indexing). Waiting uses no attempt and has a deadline (`download-wait-timeout`,
`index-wait-timeout`).

## Triggers

| Trigger | Published by | When |
|---|---|---|
| `EmailImportedEvent` | `EmailImportService` | After the email, its research data and PDF attachments are committed; not for duplicates |
| `DocumentUploadedEvent(LIBRARY)` | `DocumentService` | After a library upload is committed (attachments and downloaded reports have other origins) |
| `ReportDownloadedEvent` | `DownloadJobProcessor` | After a manual or automatic download is recorded; re-opens INDEX → MATCH → ASSESS |
| Manual | `POST /workflows/{sourceId}/process`, `POST /jobs/{id}/retry` | User request |
| Reconcile | `AutomationJobWorker` (every `reconcile-interval`) | Sources imported since activation, older than 2 minutes, without a workflow (lost event) |

Events are published only after the source is committed, so a job never references uncommitted data.
Starting is idempotent (unique source per workflow, `on conflict do nothing`); a listener never fails the
import that published the event.

**Sources imported before Milestone 2** (`workflow_settings.activated_at`, set by the migration) are not
processed automatically. `GET /workflows/{sourceId}` returns `NOT_STARTED` with
`importedBeforeAutomation: true`, and the UI offers "Process now".

## Automatic PDF download policy

A link is downloaded automatically only when **all** of these hold:

1. The email came from Gmail, and its `gmail_messages` row links to a source rule that is **enabled** and
   has **automatic PDF download** on (default off). The download stage waits briefly (up to 5 minutes)
   for Gmail sync to record that mapping, which it does right after the import.
2. The link is a research link the classifier marked as a PDF (`READY`). Navigation and promotion links
   are `SKIPPED` by the classifier and never requested.
3. The per-email (`max-downloads-per-email`, 5) and per-Gmail-sync-run (`max-downloads-per-sync-run`, 20)
   caps are not exceeded.

Otherwise the stage is SKIPPED `AUTO_DOWNLOAD_DISABLED` (uploaded EML files, rules without the option)
and the links stay available for the existing manual Download button; a manual download re-opens
indexing, matching and assessment for the email.

Requests go through `DownloadRequestService` (UrlPolicy: https, allowlisted brokerage domains, no
credentials, standard ports) and the existing worker (`ReportDownloader`: DNS/IP validation against private
networks, per-hop redirect checks, size and time limits, PDF content validation, safe temporary files).
A link becomes a document only after its content is validated; identical PDFs are reused by SHA-256 and
linked to every email that references them (`message_documents`). Original URLs are kept on the link.

## Indexing

The INDEX stage requests indexing (`KnowledgeIndexService.request`) only for sources that are untracked,
failed, or indexed with another index version, then waits for the knowledge worker. Unchanged content is
not re-embedded (source content hash and chunk-vector reuse in `KnowledgeIndexProcessor`). Without an
embedding model the stage is SKIPPED `EMBEDDINGS_NOT_CONFIGURED`; matching still runs on extracted
metadata. A failed email index fails the workflow; a failed report index makes the stage PARTIAL.

## Matching and assessment

`SourceRelevanceService` (intelligence) collects tickers already extracted from the source (indexed chunk
tickers, research items, research link ticker hints, document metadata) and compares them with the
symbols and aliases of open holdings in every portfolio. ETFs and funds match only on their own symbol;
underlying holdings are never inferred. There are no watchlists yet. Market-wide commentary is only
considered with `assess-market-context=true` (default off).

Matches are stored in `workflow_portfolio_matches` (portfolio, instrument, symbol, publication date,
holdings snapshot hash). For each matched portfolio an ASSESS job calls
`ImpactAssessmentService.createForSource`, which reuses the Phase 4 pipeline (deterministic tiers,
evidence from stored chunks, `CitationValidator`) and records `trigger_source_type/id`,
`trigger_content_sha256` (email + document checksums), `prompt_version`, `index_version` and the job id.
A unique index on (portfolio, trigger source, snapshot hash, content fingerprint) prevents duplicates: a
re-run returns the existing assessment without calling the model. A new report changes the fingerprint
and produces a new assessment; changed holdings do too.

Without a chat model the deterministic `NOT_CONFIGURED` assessment is saved. The model is never called for
sources without a direct match.

## Jobs, retries and recovery

- **Claiming:** `FOR UPDATE SKIP LOCKED` with a claim token; all result writes are token-guarded, so a
  worker whose lease expired cannot overwrite a newer attempt. Bounded worker pool (`worker.concurrency`).
- **Idempotency:** `idempotency_key` is unique (`workflowId:TYPE[:portfolio:snapshot:content]:version`).
  Enqueueing an active job is a no-op; a finished one is re-opened (same row).
- **Transient failures** (AI timeout/outage, 429/5xx, embedding outage, database unavailable, unknown
  errors) are retried with `retry-backoff × 2^(attempt-1)` and ±50% jitter, up to `max-attempts`.
- **Permanent failures** (deleted source, rejected AI request, blocked URL, invalid PDF, indexing failure)
  fail at once; the job keeps a safe `error_code` / `error_summary` (no URLs, stack traces or secrets).
- **Manual retry** resets one failed job (`POST /jobs/{id}/retry`); `POST /workflows/{id}/process` retries
  all failed jobs, or runs a finished workflow again (cheap and idempotent).
- **Restarts:** RUNNING jobs whose lease (`worker.lease`) expired return to PENDING (or FAILED when out of
  attempts). A failed download never re-imports the email; a failed embedding never repeats ingestion.
- **Source deletion:** workflows, stages, matches and jobs cascade with the email or document; in-flight
  results are dropped. Library PDFs are never deleted with an email.

## AI cost control

| Limit | Setting | Default |
|---|---|---|
| Concurrent chat calls (whole app) | `investary.ai.max-concurrent-chat` | 2 |
| Concurrent embedding batches (whole app) | `investary.ai.max-concurrent-embedding` | 1 |
| Automatic downloads per email / per Gmail sync run | `investary.workflow.max-downloads-per-email` / `-per-sync-run` | 5 / 20 |
| New automatic assessments per day (Asia/Ho_Chi_Minh) | `investary.workflow.max-auto-assessments-per-day` | 20 |
| Attempts per job | `investary.workflow.max-attempts` | 3 |

Token counts are stored on ASSESS jobs only when the provider reports them; nothing is estimated and no
monetary cost is computed. Usage dashboards belong to Milestone 4.

## API (local-only, behind `LocalOnlyRequestFilter`)

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/automation/jobs?status=&type=&workflowId=&page=&size=` | `PageResponse` of jobs, newest first |
| GET | `/api/v1/automation/jobs/{id}` | `AUTOMATION_JOB_NOT_FOUND` |
| POST | `/api/v1/automation/jobs/{id}/retry` | 202; 409 `JOB_NOT_RETRYABLE` unless FAILED |
| GET | `/api/v1/automation/workflows/{sourceId}` | Email or document id; stages, matches, jobs; `NOT_STARTED` view; 404 `WORKFLOW_NOT_FOUND` |
| POST | `/api/v1/automation/workflows/{sourceId}/process` | 202; start, retry failed jobs, or run again |

## Database (`V7__research_workflows.sql`)

`workflow_settings` (activation time), `research_workflows`, `workflow_stages`, `automation_jobs`,
`workflow_portfolio_matches`, and nullable provenance columns plus a partial unique index on
`impact_assessments`. Existing data is untouched.

## UI

`app-workflow-status` on the email and document detail pages: stage list with reasons, relevant holdings,
failed step summaries, and "Process now / again" or "Retry failed steps". It polls while running.

## Known limitations

- Caps are checked before requesting work, so with several workers the per-sync-run download cap and the
  daily assessment cap can be exceeded by at most the worker concurrency.
- Matching is by ticker and alias equality only; there is no instrument master list, watchlist or
  sector/industry mapping, and ETF constituents are not known.
- Assessments cover the whole portfolio (the Phase 4 design); the trigger source is recorded but other
  research about the same holdings is also used as evidence.
- Automatic processing of sources imported before the upgrade is manual only, by design.
- Verified with mocked services only (WireMock brokerage, fake embedding model, scripted chat model); no
  live brokerage site, Gmail account or paid model was used in tests.
