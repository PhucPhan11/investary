# Phase 5 / Milestone 3 — Automated daily investment brief

Milestone 3 adds a daily brief: what research became available since the previous brief, what it means for
each portfolio, and what the user may want to read next. It is generated on a schedule (08:00
Asia/Ho_Chi_Minh by default) or on request, cited against stored chunks only, and kept as an immutable
revision history.

```
schedule tick / POST /briefs/generate / POST /briefs/{id}/regenerate
  → brief_jobs (SKIP LOCKED queue, claim tokens, leases, idempotency keys)
  → BriefEvidenceCollector   deterministic: eligible + pending sources, dedup, ranking, holdings, assessments
  → BriefComposer            deterministic sections (facts, dates, counts, excerpts, conflicts, limitations)
  → ChatGateway (optional)   one call per brief, shared AiConcurrencyLimits, no transaction open
  → BriefValidator           CitationValidator per item, allowed labels only, trading language removed
  → briefs + brief_sources   one short token-guarded transaction; a new revision, never an overwrite
```

Out of scope (Milestone 4 and later): notification delivery (push, Telegram, e-mail), the full Automation
Center, outbound Gmail, autonomous trading, multi-agent orchestration, new services or infrastructure.

## Architecture

Briefs live in the `intelligence` module (ADR 0011): `intelligence/application/Brief*.java` (selection,
composition, prompts, validation, scheduler, worker; package-private so they can reuse `PortfolioContext` and
the assessment snapshot helpers), `intelligence/domain/` (enums), `intelligence/persistence/` (`BriefStore`,
`BriefJobQueue`, JDBC) and `intelligence/api/BriefController`.
The module already depends on exactly what a brief needs (knowledge, portfolio and investor
`*QueryService`s, research `Keywords`), so no module edge is added. Nothing imports `automation`, and
briefs never read automation tables.

The knowledge module gains `ResearchAvailabilityService`, which answers "which sources became searchable
in this window" and "which sources arrived but are not searchable yet", plus chunk lookup per source.

## Reporting periods

| Setting | Default |
|---|---|
| `investary.brief.schedule.cron` | `0 0 8 * * *` (Spring cron, seconds first) |
| `investary.brief.schedule.zone` | `Asia/Ho_Chi_Minh` (IANA id, validated at start-up) |

- A period is the half-open interval **[previous fire time, fire time)** of the cron, evaluated in the
  configured zone. With the default it is 08:00 yesterday → 08:00 today. The `businessDate` is the local
  date of the period end. The JVM default zone is never used; instants are stored as `TIMESTAMPTZ` and the
  zone id is stored on every brief.
- Zones with daylight saving are handled by evaluating the cron on `ZonedDateTime` (a period can be 23 or
  25 hours long).
- **Manual generation** takes an optional `date` (default: today in the zone). The period is the one that
  ends at that date's fire time. When that end is still in the future, the period ends at the request time
  and the brief carries the `PARTIAL_PERIOD` limitation.

## Scheduling, duplicates and missed runs

- `BriefScheduler` runs every `worker.poll-interval`. It computes the latest fire time ≤ now. When
  `now − fireTime ≤ catch-up-window` (12 h) it enqueues one SCHEDULED job per portfolio (one general brief
  when there is no portfolio), at most `max-briefs-per-run`.
- The job's idempotency key is `scheduled:{scope}:{periodEnd}:{configVersion}`, inserted with
  `on conflict do nothing`. Repeated ticks, restarts and a second instance cannot create a second job; a
  partial unique index on `briefs` (scope, period, config version, `trigger_type = 'SCHEDULED'`) is a second
  guard.
- **Missed runs:** after downtime only the most recent period within the catch-up window is generated.
  Older periods are not back-filled automatically (that could start many model calls at once); generate
  them manually with a `date`.
- **Restarts:** a RUNNING job whose lease (`worker.lease`) expired returns to PENDING (or FAILED when out of
  attempts), exactly like the M2 queue. Brief rows are written only when a job completes, so an interrupted
  job never leaves a half-written brief.
- `configVersion` is a short hash of the cron, zone, selection limits and prompt version. Changing the
  schedule starts a new series instead of colliding with existing scheduled briefs.
- Concurrency: `worker.concurrency` (1) briefs at a time; model calls also take the process-wide chat
  permits (`investary.ai.max-concurrent-chat`).

## Source eligibility

Four dates are kept apart and shown separately:

| Date | Meaning | Column |
|---|---|---|
| Publication date | Date the brokerage published the research (or the email sent date when unknown, `dateBasis = EMAIL_SENT`) | chunk `published_on` / `date_basis` |
| Email sent date | `Date:` header of the email | `source_messages.sent_at` |
| Import time | When Investary imported the email or the PDF was uploaded/downloaded | `imported_at` / `uploaded_at` |
| Index time | When the source **first** became searchable | `knowledge_sources.first_indexed_at` (new) |

- **Eligible (newly available):** the knowledge source is INDEXED with the current index version and its
  `first_indexed_at` is in [period start, period end) and not after the generation cutoff. Reindexing (for
  example after an index-version bump) keeps `first_indexed_at`, so old research never reappears as new.
- **Old research in a new email:** when the publication date is more than `old-publication-days` (7) before
  the period start, the entry says "received in this period, published on X". It is never called newly
  published. Undated sources are listed with a limitation.
- **Not yet processed:** sources imported or uploaded in the period whose knowledge source is missing,
  PENDING, PROCESSING, FAILED or SKIPPED. They are listed (title, type, state) but not summarised.
- **Research state** of a brief: `NEW_RESEARCH`; `NO_MATERIAL_RESEARCH` (nothing eligible and nothing
  pending); `PENDING_ONLY` (nothing eligible, but some sources are still being processed or failed).

### Late arrivals and late indexing

A source belongs to the brief of the period in which it became searchable. A report imported at 07:50 and
indexed at 08:10 appears in the **next** brief, flagged `lateArrival` (imported before the period started).
Regenerating a period keeps the same window, so a late source is never reported twice. This is
deterministic and needs no back-dating.

### Deduplication and ranking

- One entry per document, even when several emails link or attach the same PDF (documents are already
  unique by SHA-256).
- An email whose linked or attached PDF is also eligible is folded into that document's entry (the email is
  listed as "received via").
- Chunks with identical content hashes are kept once.
- Ranking: direct holding match → mention of a holding → market commentary → other tickers; newest
  publication first. Selection stops at `max-sources` (12) sources, `chunks-per-source` (2) chunks each and
  `max-context-chars` (14 000) characters; the remainder is listed as omitted.

Portfolio context: the current holdings snapshot (`PortfolioQueryService.snapshot`), impact assessments
created during the period for that portfolio (category per symbol), the investor profile and goals.

## Content (Vietnamese)

Sections, in order. Every item has a `kind`:
`PORTFOLIO_FACT` (computed by Investary from user data), `SOURCE_CLAIM` (a brokerage statement, cited) or
`AI_INTERPRETATION` (model text, validated, labelled as interpretation).

| Section | Deterministic part | Model part (optional) |
|---|---|---|
| Executive summary | Counts of new sources, brokerages, holdings touched, pending sources | 2–4 cited sentences |
| Market overview | Lists new market-wide sources; otherwise "no new market research; Investary has no market prices" | Cited summary, only when market sources exist |
| My portfolio updates | Per (holding, source): instrument, source, publication date, relevance (DIRECT/MENTION/MARKET), verbatim excerpt, citation | Cited key finding per holding, only from that holding's sources |
| Research opportunities | Tickers not held, ordered as reading priorities (direct report, newest) | Cited one-liners |
| Risks and conflicting views | Same ticker covered by ≥2 brokerages with different rating stances (conservative rating-term detection) | Cited risks |
| Suggested follow-up actions | Read X; check pending sources; update stale manual prices | Suggestions without trading instructions |
| Sources and limitations | Brokerages, dates, page/section references, old/undated/pending/omitted warnings, AI status | — |

No price, index level or market movement is ever produced by Java code unless it is a stored manual price
already in the portfolio snapshot; model text with figures not found in the cited source is downgraded.

## Citation validation

- Sources are labelled S1..Sn by the backend and rendered with `PromptBuilder.sources` inside `<source>`
  elements; instructions live only in the system message.
- Each model text goes through `CitationValidator` with only the labels it may cite (a portfolio finding:
  that holding's sources; the summary: all sources). Labels outside the evidence set are removed and the
  status lowered; uncited figures and sentences that do not match their source lower the status.
- Findings for symbols not in the portfolio, or with no valid citation, are dropped with a warning; the
  deterministic item remains.
- Follow-up suggestions containing trading instructions (mua/bán/chốt lời/cắt lỗ/đặt lệnh/buy/sell, …)
  are dropped.
- Citations are built from stored chunks (`Citations.of`) and saved in `brief_sources`; model output never
  supplies ids, titles, pages or dates.
- A brief can be partially supported: every section and item carries its evidence status, and the brief's
  overall evidence status is the lowest of them.

## Generation outcomes

| Situation | Result |
|---|---|
| Model answered and validated | `COMPLETED` |
| No chat model configured | `PARTIAL`, deterministic sections only, `aiStatus = NOT_CONFIGURED` |
| Timeout / retryable provider error | Job retried with backoff; after `max-attempts`, `PARTIAL` deterministic brief with the error code |
| Non-retryable provider error | `PARTIAL` deterministic brief at once |
| Database or unexpected error | Retried; after `max-attempts` the job is FAILED and a `FAILED` brief row records the safe summary |
| Nothing eligible, nothing pending, `no-research-mode = MINIMAL` (default) | `COMPLETED` minimal brief "no material new research", no model call |
| Same, `no-research-mode = SKIP` | `SKIPPED` row (keeps the period, hidden from the default list) |
| Nothing eligible but sources pending | Minimal brief with `PENDING_ONLY`, regardless of the mode |

**No duplicate model calls:** each generation computes an input fingerprint (prompt version, index version,
model label, holdings snapshot hash, profile and goals, evidence chunk ids and hashes, period). If the
latest brief of the same scope and period has the same fingerprint and a successful model result, the job
finishes with outcome `UNCHANGED` and points to that brief; no model call and no new revision.

Only provider-reported token counts are stored; nothing is estimated.

## Regeneration and revisions

`POST /briefs/{id}/regenerate` re-runs the same scope and period. A changed input creates revision n+1
(`root_brief_id`, `previous_revision_id`); the old revision is kept and listed in the detail view. The list
shows only the latest revision of each chain.

## Persistence (`V8__daily_briefs.sql`)

- `knowledge_sources.first_indexed_at` (back-filled from `completed_at` for INDEXED sources).
- `brief_jobs`: queue (status, attempts, lease, claim token, idempotency key, outcome, brief id, safe error,
  provider-reported tokens).
- `briefs`: scope, period, zone, business date, trigger, status, research state, content (JSON sections),
  limitations, AI status/model, prompt/index/config versions, input fingerprint, portfolio snapshot and
  hash, source cutoff, evidence status, error, tokens, revision chain.
- `brief_sources`: one row per selected, pending or omitted source with label, chunk id and hash, the four
  dates, page/section, excerpt, symbols, relevance and flags. Source foreign keys are `ON DELETE SET NULL`
  so a brief stays readable after a source is deleted (its citation is then marked unavailable).

## API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/briefs?portfolioId=&from=&to=&status=&page=&size=` | `PageResponse`, latest revision per chain, newest period first |
| GET | `/api/v1/briefs/{id}` | Sections, citations, sources, limitations, revisions; 404 `BRIEF_NOT_FOUND` |
| POST | `/api/v1/briefs/generate` | Body `{portfolioId?, date?}`; 202 with the job (Location `/api/v1/briefs/jobs/{id}`) |
| GET | `/api/v1/briefs/jobs/{id}` | Job status, attempts, outcome, brief id, safe error; 404 `BRIEF_JOB_NOT_FOUND` |
| POST | `/api/v1/briefs/{id}/regenerate` | 202 with the job |

`BRIEFS_DISABLED` (409) when `investary.brief.enabled=false` and a generation is requested.

## Configuration

| Setting | Env | Default |
|---|---|---|
| `investary.brief.enabled` | `INVESTARY_BRIEF_ENABLED` | `true` |
| `investary.brief.schedule.cron` | `INVESTARY_BRIEF_CRON` | `0 0 8 * * *` |
| `investary.brief.schedule.zone` | `INVESTARY_BRIEF_ZONE` | `Asia/Ho_Chi_Minh` |
| `investary.brief.catch-up-window` | `INVESTARY_BRIEF_CATCH_UP_WINDOW` | `12h` |
| `investary.brief.no-research-mode` | `INVESTARY_BRIEF_NO_RESEARCH_MODE` | `MINIMAL` |
| `investary.brief.max-sources` / `chunks-per-source` / `max-context-chars` | — | 12 / 2 / 14000 |
| `investary.brief.old-publication-days` | — | 7 |
| `investary.brief.max-briefs-per-run` | — | 5 |
| `investary.brief.max-attempts` / `retry-backoff` | — | 3 / 1m |
| `investary.brief.worker.enabled` / `concurrency` / `poll-interval` / `lease` | `INVESTARY_BRIEF_WORKER_ENABLED` | true / 1 / 30s / 15m |

## UI

`/briefs` lists briefs (portfolio and date filters, status, research state) and has "Generate now"
(portfolio, date) that polls the job. `/briefs/:id` shows the period and zone, status, sections as plain text
with citation buttons (`answerSegments`), item kinds, the citation panel linking to `/documents/:id` and
`/emails/:id`, sources with their four dates, limitations, revisions and "Regenerate". No `innerHTML`.

## Known limitations

- "Not yet processed" uses the knowledge index state; it does not read the M2 workflow stages, so a source
  that is indexed but still waiting for a download of its linked report is treated as available.
- Rating-conflict detection recognises explicit rating labels only (e.g. "Khuyến nghị: MUA"); differently
  worded opinions are left to the model, which must cite both sides.
- Missed periods older than the catch-up window are only generated on request.
- Tests use the fake embedding model and the scripted chat model; no real LLM, brokerage or Gmail account is
  used in automated tests.
