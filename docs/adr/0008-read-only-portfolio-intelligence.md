# ADR 0008 — Read-only portfolio intelligence on the existing RAG pipeline

Status: accepted (Phase 4, 2026-10-30)

## Context

Phase 4 connects holdings, the investor profile and goals with the Phase 3 knowledge base. Combining
personal financial data with an LLM raises three risks: the model changing data, the model presenting
its own reading as fact or advice, and a second, unvalidated retrieval path.

## Decision

- **A separate `intelligence` module** depends on `knowledge`, `portfolio`, `investor` (and `research` for
  text normalisation). Nothing depends on it. It may only use the `PortfolioQueryService` and
  `InvestorQueryService` read services; `ArchitectureTest` enforces this, and no model output is ever
  written to portfolio, transaction or profile tables. No tool calling.
- **No second RAG pipeline.** Matching calls `HybridSearchService`; prompts reuse `PromptBuilder.sources`;
  citations come from `Citations.of` (stored chunks); answers go through `CitationValidator`; model calls
  go through the shared `ChatGateway` (timeout, error mapping).
- **Deterministic first.** Research matching, the analysis and the baseline assessment category need no
  AI. The model only interprets, may only choose categories allowed by the evidence tier, and may only
  cite the evidence of the holding it describes; otherwise the deterministic result is kept.
- **Three kinds of information stay separate** in prompts and responses: backend portfolio facts,
  source evidence (cited chunks) and AI interpretation (labelled).
- **Saved assessments are immutable snapshots** with a holdings hash and evidence hashes; staleness is
  computed on read rather than by events, so it also covers reindexing in `knowledge`.
- **Explicit portfolio.** The backend never chooses a portfolio for a question (`PORTFOLIO_REQUIRED`).

## Consequences

- The AI cannot cause data loss or silent changes; the worst case is a wrong but labelled and cited
  interpretation.
- Categories like "potential risk" are constrained and cited but not semantically verified.
- Any future "act on this" feature must be a separate, user-confirmed command outside `intelligence`.
