# Phase 6 / Milestone 1 — Investment Decision Journal

Milestone 1 adds an auditable journal of investment decisions: what the user decided, why, what they expected,
what would prove them wrong, which research they relied on, what the portfolio looked like at that moment, and
which real portfolio transactions carried the decision out.

The journal records decisions; it does not make, score or execute them. Portfolio transactions remain the only
financial source of truth: the journal never creates trades, never infers execution from holdings and never
changes holdings, valuation or allocation calculations.

Out of scope: thesis monitoring, scheduled reviews or reminders, brokerage scoring, AI-generated decisions,
trading, new services or infrastructure.

## Module

`com.investary.journal` (`api / application / domain / persistence`). It may use:

| Module | Through | For |
|---|---|---|
| `portfolio` | `PortfolioQueryService` only | portfolio, instrument and transaction lookups, the snapshot |
| `investor` | `InvestorQueryService` only | risk profile and goals in the snapshot |
| `document` | `DocumentService` (read methods) | PDF evidence |
| `email` | `EmailQueryService` | email evidence |
| `research` | `ResearchQueryService` | research item evidence |
| `knowledge` | `ChunkLookupService` | citation (stored chunk) evidence |
| `intelligence` | `ImpactAssessmentService.view` | impact assessment evidence |

Nothing imports `journal`. `ArchitectureTest` enforces the map and the read-only rule (`*QueryService` only for
`portfolio` and `investor`). See ADR 0013.

## Domain model

### Decision (`investment_decisions`)

| Field | Notes |
|---|---|
| `portfolioId` | Optional. Without it the decision is a watchlist decision. A copy of the portfolio name is kept. |
| `instrumentId` | Optional only for `REBALANCE`. A copy of the symbol is kept. |
| `decisionType` | `BUY`, `SELL`, `HOLD`, `REBALANCE`, `OBSERVE` |
| `status` | `DRAFT`, `DECIDED`, `EXECUTED`, `CANCELLED` |
| `decisionDate` | Business date (defaults to today in Asia/Ho_Chi_Minh) |
| `title`, `rationale`, `expectedOutcome`, `timeHorizon`, `mainAssumptions`, `risks`, `invalidationConditions`, `reviewDate`, `notes` | The user's reasoning |
| `intendedQuantity`, `intendedAllocationPct`, `intendedPrice` | Optional intentions. **Never** treated as orders or executed trades and never used in any calculation. |
| `finalizedAt`, `executedAt`, `cancelledAt`, `cancelReason` | Lifecycle timestamps |
| `version` | Optimistic lock |

Deleting a portfolio, instrument or source never deletes a decision: foreign keys are `ON DELETE SET NULL` and
the copied names stay.

### Lifecycle

```
DRAFT ──finalize──▶ DECIDED ──execute──▶ EXECUTED
  │                    │
  └──────cancel────────┴──────▶ CANCELLED
```

| Action | From | Rule |
|---|---|---|
| `finalize` | `DRAFT` | Requires a rationale; takes the snapshot (once). |
| `execute` | `DECIDED` | `BUY`, `SELL` and `REBALANCE` need at least one linked transaction. `HOLD` and `OBSERVE` complete without one. The user triggers it explicitly; nothing infers it. |
| `cancel` | `DRAFT`, `DECIDED` | Requires a reason; the history stays. |

Any other transition is rejected with 409 `INVALID_DECISION_TRANSITION`. `EXECUTED` and `CANCELLED` are final.

### Editing and audit

- A `DRAFT` is freely editable (`PUT`).
- After finalization, `PUT` is an **amendment**: it needs an `amendmentReason` and may change only the narrative
  fields (title, rationale, expected outcome, time horizon, assumptions, risks, invalidation conditions, review
  date, notes). Type, portfolio, instrument, decision date and intended quantity/allocation/price describe what
  the snapshot captured and are locked (409 `DECISION_FIELD_LOCKED`). To change them, cancel and record a new
  decision.
- A `CANCELLED` decision is read-only, but its evidence and links remain visible.
- Every change appends a row to `decision_revisions` (sequence, kind, time, reason, status after, and a JSON diff
  `{"field": {"from": …, "to": …}}` or the attached/removed item). Kinds: `CREATED`, `EDITED`, `FINALIZED`,
  `AMENDED`, `EXECUTED`, `CANCELLED`, `EVIDENCE_ATTACHED`, `EVIDENCE_REMOVED`, `TRANSACTION_LINKED`,
  `TRANSACTION_UNLINKED`. A database trigger rejects `UPDATE` and `DELETE` on revisions and snapshots.
- Evidence and transaction links are never deleted: removal sets `removed_at` and a reason and writes a revision.
- Every write carries `expectedVersion`. A stale version, or a concurrent write that wins the JPA `@Version`
  race, returns 409 `DECISION_CONFLICT`.

### Snapshot (`decision_portfolio_snapshots`)

Taken once, at finalization, in the same transaction, and never updated (JSONB). Built only from the Phase 4
query results (`PortfolioQueryService.snapshot` and `allocation`, which use `HoldingCalculator` and `Money`):

- portfolio name, currency, valuation date, valuation status, totals and the portfolio's own limitations;
- every open holding: quantity, average cost, cost basis, cost weight, and — only when a price observation
  exists — price, price date, market value and market weight;
- the decision instrument's position (or "not held"), with its latest price observation if any;
- the list of holdings without a price observation (`missingPrices`); no value is estimated for them;
- cost-basis allocation, and market-value allocation only when the portfolio module provides it;
- the investor profile (risk tolerance, horizon, experience, objectives) and all goals.

Watchlist decisions record the investor part and the instrument's latest price observation, if one exists.
Later transactions or prices do not change a stored snapshot.

### Evidence (`decision_evidence`)

| Kind | Reference | Copied at attach time |
|---|---|---|
| `DOCUMENT` | library PDF | title / filename, upload date |
| `EMAIL` | imported email | subject, sent date, sender |
| `RESEARCH_ITEM` | research item | title, published date, ticker, brokerage, location, excerpt |
| `CITATION` | stored knowledge chunk | source title, published date, page/section location, verbatim excerpt |
| `IMPACT_ASSESSMENT` | impact assessment | portfolio, date, summary |

Roles: `SUPPORTING`, `CONTRADICTING`, `BACKGROUND`. The reference must exist when attached (404
`EVIDENCE_SOURCE_NOT_FOUND`); the same active source cannot be attached twice (409 `DUPLICATE_EVIDENCE`).
Excerpts are verbatim, at most 1,000 characters. When a source is deleted its foreign key becomes `null`; the
evidence then shows as unavailable with its copied title, date, location and excerpt. Another source is never
substituted. A citation is unavailable as soon as its chunk is no longer current (reindexed or deleted).

### Transaction links (`decision_transaction_links`)

The user links existing portfolio transactions (any number) to a decision; the journal copies the trade facts
(type, date, quantity, price, symbol) at link time. Rules:

- the decision must have a portfolio and the transaction must belong to it;
- `BUY` decisions accept `BUY` or `OPENING_POSITION` transactions of the same instrument; `SELL` accepts `SELL`
  of the same instrument; `REBALANCE` accepts any transaction of the portfolio; `HOLD` and `OBSERVE` may link
  transactions of the same instrument but never need one;
- not allowed on a `CANCELLED` decision; one active link per transaction and decision.

The detail view shows the current transaction (read through `PortfolioQueryService`) and flags a link whose
transaction was changed or deleted since it was linked. Unlinking is a soft removal with a reason and a revision.

## API (`/api/v1/decisions`)

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/` | Create a draft |
| `GET` | `/?portfolioId&ticker&from&to&status&type&page&size` | List (newest decision date first) |
| `GET` | `/{id}` | Detail: fields, snapshot, evidence, links |
| `PUT` | `/{id}` | Edit a draft, or amend a finalized decision (`amendmentReason`) |
| `POST` | `/{id}/finalize` | `DRAFT → DECIDED`, takes the snapshot |
| `POST` | `/{id}/execute` | `DECIDED → EXECUTED` |
| `POST` | `/{id}/cancel` | `→ CANCELLED` (`reason`) |
| `GET` | `/{id}/history` | Revisions, oldest first |
| `POST` | `/{id}/evidence` | Attach evidence |
| `DELETE` | `/{id}/evidence/{evidenceId}?expectedVersion&reason` | Remove evidence (audited) |
| `POST` | `/{id}/transactions` | Link a transaction |
| `DELETE` | `/{id}/transactions/{linkId}?expectedVersion&reason` | Unlink (audited) |

Errors are problem+json with the codes listed above, `DECISION_NOT_FOUND`, `EVIDENCE_NOT_FOUND`,
`TRANSACTION_LINK_INVALID`, `DUPLICATE_TRANSACTION_LINK`, `TRANSACTION_LINK_NOT_FOUND` and `VALIDATION_FAILED`.

## UI (`/journal`)

- List with filters (ticker, portfolio, status, type) and paging; "New decision".
- Form: create, edit draft, amend (reason required, locked fields read-only).
- Detail: fields and status actions (finalize, execute, cancel), the snapshot (with missing-price notices),
  evidence (attach by kind and role, remove), linked transactions (pick from the portfolio's transactions, unlink),
  and the revision history with diffs.

## Known limitations

- No link between a decision and specific goals; the snapshot includes all goals.
- No thesis monitoring, review reminders, outcome scoring or AI help; these are later milestones.
- Locked fields cannot be amended; cancel and record a new decision instead.
- Citation evidence is only "available" while its chunk is current in the knowledge index.
