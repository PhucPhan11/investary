# ADR 0013 — Investment decision journal as a read-only `journal` module

## Status

Accepted (Phase 6 / Milestone 1).

## Context

The user wants to record investment decisions with their reasoning, the evidence behind them, the portfolio
context at the moment of deciding and the real trades that carried them out, and to keep that record auditable.
The journal must never become a second source of financial truth: holdings, valuation and allocation are computed
from portfolio transactions (ADR 0007), and transactions must not be created or inferred from journal entries.
Evidence comes from five modules (`document`, `email`, `research`, `knowledge`, `intelligence`), and any of those
sources may be deleted later.

## Decision

- **New module `journal`** (`api / application / domain / persistence`). It reads `portfolio` and `investor` only
  through `PortfolioQueryService` / `InvestorQueryService` (the same `ArchitectureTest` rule as `intelligence`) and
  looks up evidence through the owning modules' application services. Nothing imports `journal`, so it can depend on
  every module without a cycle. Putting it in `intelligence` was rejected: decisions are the user's records, not
  derived intelligence, and `intelligence` would gain write responsibilities it does not have today.
- **Lifecycle** `DRAFT → DECIDED → EXECUTED`, or `DRAFT | DECIDED → CANCELLED`, through explicit endpoints
  (`finalize`, `execute`, `cancel`). Execution is a user action; BUY, SELL and REBALANCE require at least one linked
  transaction, HOLD and OBSERVE none. Nothing is inferred from holdings.
- **Snapshot once.** Finalization copies the portfolio query results (holdings, cost basis, weights, prices that
  exist, valuation status and date, limitations, allocation) plus the investor profile and goals into one JSONB row.
  Missing prices are listed, never estimated. The row is immutable (database trigger).
- **Append-only audit.** Every write checks the client's `expectedVersion`, relies on JPA `@Version` for concurrent
  writers (409 `DECISION_CONFLICT`), and appends a `decision_revisions` row with a JSON field diff. After finalization
  only narrative fields can be amended (with a reason); the fields the snapshot describes are locked. Evidence and
  transaction links are soft-removed with a reason. A trigger rejects updates and deletes of revisions and
  snapshots unless their decision is already gone (no API deletes decisions).
- **Evidence and links as copies.** One nullable reference column per evidence kind with `ON DELETE SET NULL`, the
  original id and copied title, publisher, date, location and excerpt. A deleted source, or a cited chunk that is no
  longer current, is shown unavailable; no other source is substituted. Transaction links copy type, date, quantity
  and price at link time and show whether the transaction was changed or deleted since.

## Alternatives considered

- **Event sourcing for decisions:** complete history, but a new persistence style for one module; a current-state
  row plus an append-only revision table gives the same audit with the existing JPA conventions.
- **Recomputing the snapshot on read:** would silently change what the decision was based on.
- **Blocking deletion of referenced sources or transactions:** would make the journal control other modules'
  retention; copies plus `SET NULL` keep both independent.
- **Inferring execution from new transactions:** contradicts the requirement that transactions stay the only
  financial truth and would guess intent.

## Consequences

- Five new tables (V10) and small read-only lookups in `portfolio`, `research` and `knowledge`.
- Locked fields cannot be corrected after finalization; the user cancels and records a new decision, and both stay
  in the history.
- Snapshots hold copies of portfolio figures as they were; they intentionally diverge from today's values.
