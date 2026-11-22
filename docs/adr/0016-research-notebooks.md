# ADR 0016 — Research notebooks as a read-only `notebook` module

## Status

Accepted (Phase 6 / Milestone 2).

## Context

The user wants to work across a chosen set of investment reports the way NotebookLM-style tools allow: pick the
sources, ask follow-up questions over them, and get generated write-ups ("statements"), each with citations. Phase 3
already has the hard parts: hybrid search, S1..Sn labelling, `PromptBuilder` with untrusted `<source>` text,
`CitationValidator` and `ChatGateway`. The brief pipeline (Phase 5 / Milestone 3) already shows the pattern for
deterministic evidence, per-item validation and a token-guarded job queue. What was missing:

1. Retrieval limited to chosen sources. `SearchFilters` had no source-id filter.
2. A saved grouping of sources and a chat history. `/research/ask` answers each question on its own.
3. Generated outputs over a fixed set of sources.

## Decision

- **New module `notebook`** (`api / application / domain / persistence`). It depends on `knowledge`, `research`,
  `document` and `email` application services only. Nothing imports it. It never touches `portfolio`, `investor` or
  `intelligence`: notebooks are about research, so AI output there cannot reach portfolio data.
- **Scoped retrieval in `knowledge`.** `SearchFilters` gains `documentIds` / `messageIds`.
  - Both `null` means unrestricted.
  - An empty scope means "no sources": `HybridSearchService` returns nothing and never embeds the query.
  - The old five-argument constructor stays, so existing callers do not change.
- **A notebook is a fixed list of sources.** A filter (ticker, brokerage, type, dates) is resolved to concrete ids
  once, when sources are added. New research does not change a notebook silently. Unindexed sources are listed with
  their state, and are never used.
- **Chat history is context, never evidence.**
  - Earlier turns go into `<previous_turn>` elements with their citation markers removed and their text neutralised,
    and the system message says they cannot be cited.
  - Retrieval uses the previous question plus the new one. This is deterministic: no model call rewrites the question.
  - The answer goes through the unchanged `ResearchAssistantService` validation.
  - The question, the answer and its citations are written in one transaction, after the model call.
- **Notes (SUMMARY, CONSENSUS, TIMELINE, FAQ)** run on a queue in which each `notebook_notes` row is also its own job.
  It copies the `brief_jobs` pattern: SKIP LOCKED claims, a claim token on every write, backoff, and a worker that is
  off in tests.
  - Evidence is chosen in Java: per-ticker filtering, at most `note-sources` sources, and chunks interleaved so each
    source gets one chunk before any gets a second.
  - Source groups (G1, G2, …) are built from stored metadata, so the model can refer to a whole source.
  - `NoteComposer` validates every model text against only the labels it may cite (a source's own labels for its
    view), drops uncited items and trading instructions (`TradingLanguage`, shared with briefs), and can only lower
    the evidence status.
  - Titles, publishers, dates, date basis and extracted brokerage ratings come from stored records, never from the model.
- **Identical inputs reuse the note.** The fingerprint covers prompt version, kind, ticker, index version, model label,
  limits and each source's indexed-text hash. A matching DONE note is returned with no model call.
- **Citations are copies.** `notebook_citations` keeps the title, location, excerpt and chunk hash. The source
  reference becomes NULL (`ON DELETE SET NULL`) when the document or email is deleted, and the UI shows the source as
  deleted instead of substituting another one.
- **No streaming.** Answers arrive whole after validation, like `/research/ask`. Streaming would show text before
  its citations are checked.

## Consequences

- One more module for `ArchitectureTest` (`nothingImportsNotebook`). `ALLOWED` now uses `Map.ofEntries`
  (`Map.of` takes at most ten pairs).
- `ai_usage_daily` accepts the purpose `NOTEBOOK_NOTE`. Notebook chat is recorded as `RESEARCH_ASSISTANT`, because it
  goes through the research assistant.
- Daily note generation is capped (`investary.notebook.daily-note-limit`). Reused notes do not count.
- Long notebooks are summarised from a bounded evidence package. Omitted sources are listed under the note's
  limitations, never silently ignored.
