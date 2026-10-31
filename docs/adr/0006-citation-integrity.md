# ADR 0006 — Citation integrity and evidence states

- Status: accepted
- Date: 2026-10-29

## Context

The assistant answers investment questions from brokerage research. A fabricated price, a citation to
a document that does not say what the answer claims, or a brokerage rating presented as Investary's
advice are the worst failures this product can have. LLMs invent identifiers and numbers, and source
documents (emails especially) may contain text that tries to instruct the model.

## Decision

1. **The backend owns citation identity.**
   - Retrieved chunks are labelled `S1…Sn` before the model sees them.
   - The model may only refer to labels. Citation records (ids, titles, dates, pages, sections,
     excerpts) are built from stored chunks, never from model text.
2. **Model output is validated deterministically** (`CitationValidator`).
   - Unknown labels are removed.
   - Figures in cited sentences must appear in the cited chunks.
   - Cited sentences must share content words with their sources.
   - Uncited figures are reported.
   - The validator can only lower the evidence status.
3. **Evidence states:**
   - `SUPPORTED`: all material claims cited, and the citations pass validation.
   - `PARTIAL`: a warning lists what is unverified or missing, including brokerages with no evidence.
   - `INSUFFICIENT`: nothing relevant was retrieved (then the model is **not called**), or no valid
     citation remains.
4. **Instructions only in the system message.** Retrieved text goes into `<source>` elements in the user
   message, is described as untrusted data, and has tag-like sequences neutralised.
5. **Third-party opinions stay attributed.** Ratings are reported as the brokerage's opinion with their
   date. Every answer carries a disclaimer that it is not investment advice and may be outdated.

## Consequences

- Answers may be downgraded even when a human would accept them, e.g. a correct figure written as
  "78,5 nghìn" when the source says "78.500". We prefer false caution to false confidence.
- The checks cannot prove semantic support; they catch fabricated identifiers and numbers, which are
  the most harmful errors. Semantic groundedness needs a live-model evaluation (Phase 4).
