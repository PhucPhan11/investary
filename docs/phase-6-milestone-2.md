# Phase 6 / Milestone 2 — Research notebooks

A NotebookLM-style workspace over the existing knowledge base. A notebook is a fixed set of library documents and
emails. You can chat with only those sources, with history, and generate cited notes from them. Decision record:
[ADR 0016](adr/0016-research-notebooks.md).

## User flow

1. **Notebooks → Create notebook** (name, optional description).
2. **Add sources**, in one of three ways:
   - tick recent library reports;
   - tick imported emails (searchable);
   - add by filter (ticker, brokerage, type, date range). The filter is resolved to the matching indexed sources once.
   Sources that are not indexed show their state (for example *Not indexed*) with an **Index** action, and are not
   used until they are searchable.
3. **Chat.** Questions are answered only from the notebook's sources.
   - Follow-up questions see up to `chat-history-turns` earlier turns as context.
   - Answers carry citation buttons and a source list (excerpt, page or section, date).
   - **Clear conversation** removes the history; notes stay.
4. **Notes.** Generate one of four kinds. A note runs in the background and the page polls until it is done.

   | Kind | Content |
   |---|---|
   | Briefing summary | Up to 8 key points across all sources, each cited |
   | Consensus and disagreement (per ticker) | Per source: date and date basis, brokerage, title and the rating as extracted from the email (Java); one cited view line (model). Plus up to 4 agreements and 4 disagreements, cited |
   | Timeline of views (per ticker) | The same per-source rows, oldest first |
   | Suggested questions | Up to 6 questions with cited answers; **Ask this** sends a question to the chat |

   Generating the same note again with unchanged inputs returns the earlier note (`reused`) with no model call.
   A failed note can be retried.

## Rules

- Retrieval and evidence are limited to the notebook's sources (`SearchFilters.scopedTo`). An empty scope never
  calls the model.
- Instructions live only in system messages. Source text stays in `<source>` elements; history goes in
  `<previous_turn>` with its citation markers removed and its text neutralised.
- Every model text is validated against the labels it may cite (`CitationValidator`). Uncited items and trading
  instructions (`TradingLanguage`) are dropped and listed under the note's limitations. The status can only go down.
- Ratings in consensus and timeline notes are copied from extracted research items and labelled as the brokerage's
  statement, never Investary advice. Target prices are never extracted by Java: they appear only in validated,
  cited model text.
- Citations are copies. A deleted source shows **Source deleted** with the copied title and excerpt.
- Logs contain ids, counts and statuses only.

## Data (V11)

| Table | Purpose |
|---|---|
| `notebooks` | title, description, `version` (optimistic `expectedVersion` on rename) |
| `notebook_sources` | one document **or** email per row; `ON DELETE CASCADE` from the source |
| `notebook_messages` | USER / ASSISTANT turns, evidence status, warnings |
| `notebook_notes` | note and job in one row: kind, ticker, status, fingerprint, claim token, attempts, content JSON, limitations |
| `notebook_citations` | copies for a message **or** a note; source reference `ON DELETE SET NULL` |

## API (`/api/v1/notebooks`)

| Method | Path | Result |
|---|---|---|
| `GET` / `POST` | `/` | list · create `{title, description?}` → `201` |
| `GET` / `PATCH` / `DELETE` | `/{id}` | detail (sources with index state, tickers) · rename `{title, description?, expectedVersion}` (`409 NOTEBOOK_CONFLICT`) · delete |
| `POST` | `/{id}/sources` | `{documentIds?, messageIds?, filter?}` · `422 NOTEBOOK_SOURCE_LIMIT` |
| `DELETE` | `/{id}/sources/{sourceId}` | remove one source |
| `GET` / `DELETE` | `/{id}/messages` | conversation · clear it |
| `POST` | `/{id}/ask` | `{question}` → `{question, answer, coverageGaps, disclaimer}` · `422 NOTEBOOK_EMPTY` |
| `GET` / `POST` | `/{id}/notes` | list · request `{kind, ticker?}`: `202` new, `200` reused · `400` without a ticker for CONSENSUS/TIMELINE · `429 NOTE_LIMIT_REACHED` |
| `GET` | `/{id}/notes/{noteId}` | note with content, limitations and citations |
| `POST` | `/{id}/notes/{noteId}/retry` | `202` · `409 NOTE_NOT_RETRYABLE` unless FAILED |

## Configuration (`investary.notebook.*`)

| Key | Env | Default |
|---|---|---|
| `max-notebook-sources` | `INVESTARY_NOTEBOOK_MAX_SOURCES` | 200 |
| `chat-history-turns` | `INVESTARY_NOTEBOOK_CHAT_HISTORY_TURNS` | 6 |
| `note-sources` | `INVESTARY_NOTEBOOK_NOTE_SOURCES` | 20 |
| `daily-note-limit` | `INVESTARY_NOTEBOOK_DAILY_NOTE_LIMIT` | 30 |
| `worker.enabled` | `INVESTARY_NOTEBOOK_WORKER_ENABLED` | true |
| `chat-sources`, `chunks-per-source`, `max-context-chars`, `max-attempts`, `retry-backoff`, `ai-timeout`, `worker.poll-interval`, `worker.lease` | — | 8, 3, 16000, 3, 1m, `INVESTARY_AI_TIMEOUT`, 5s, 15m |

## Tests

- **Backend:**
  - `NoteComposerTest`: per-source labels, uncited and trading items, figures checked against sources, stored ratings, ordering.
  - `PromptBuilderTest`: history elements, markers stripped, no tag escape.
  - `NotebookControllerTest`.
  - `NotebookIntegrationTest`: scoped chat with no model call outside the scope, history, filter resolution, version
    conflict, all four notes through `claim` + `process`, reuse, retries to FAILED and manual retry, a citation
    outliving its deleted source.
- **Frontend:** `notebook-api.service.spec`, `notebook-list.spec`, `notebook-detail.spec` (citations as buttons,
  ask, consensus rendering, ticker note with polling, source picker).

## Not in this milestone

Streaming answers, notes that combine notebooks, audio overviews, export, and editing note text.
