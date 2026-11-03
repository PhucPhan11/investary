# Phase 4 — Portfolio-aware investment intelligence

Status: implemented on branch `phase-3-knowledge-base` (not committed). Date: 2026-10-30.

## Goal

Let Investary know what the user owns and wants, and connect that to the Phase 3 research knowledge base:
an investor profile and goals, transaction-based portfolios with deterministic holdings and valuation,
research matched to holdings, saved impact assessments, and a portfolio-aware assistant. The AI only
reads portfolio data; facts computed by the backend, source evidence and AI interpretation are always
kept apart. There is no trading, broker sync, live market data, signal or forecast.

## Decisions

| Topic | Decision |
|---|---|
| Modules | `investor` and `portfolio` (both depend only on `common`), `intelligence` (depends on `knowledge`, `portfolio`, `investor`, `research`). Nothing imports `intelligence` |
| Read-only AI | `intelligence` may only use `*QueryService` classes of `portfolio` / `investor` (`ArchitectureTest.intelligenceUsesOnlyReadOnlyPortfolioAndInvestorServices`). See [ADR 0008](adr/0008-read-only-portfolio-intelligence.md) |
| Accounting | Weighted-average cost, fees and taxes in the cost, chronological replay, BigDecimal `DECIMAL128`, display rounding only. See [ADR 0007](adr/0007-portfolio-accounting.md) |
| Prices | Manual observations (`price`, `asOf`, required `source`); no market feed |
| Retrieval | Reuses `HybridSearchService`, `PromptBuilder` (new reusable `sources()`), `Citations`, `CitationValidator` and the new `ChatGateway` extracted from the Phase 3 assistant; no second index or RAG pipeline |
| Staleness | Computed on read: holdings hash and cited chunk hashes are compared with the stored copies |

## Schema

- **`V4__investor_portfolio.sql`**
  - `investor_profile`: at most one row (unique index on `((true))`); experience, risk tolerance
    (`LOW`/`MODERATE`/`HIGH`), objectives, horizon (`SHORT`/`MEDIUM`/`LONG`), `preferred_instruments text[]`,
    restrictions, liquidity needs, notes.
  - `investor_goals`: name, description, optional `target_amount` (> 0) and `target_date`, currency, priority, notes.
  - `instruments`: unique `symbol`, name, type (`STOCK`/`ETF`/`FUND`/`BOND`/`OTHER`), exchange, currency,
    `aliases text[]` (other names used for research matching).
  - `portfolios`: name (unique, case-insensitive), description, `base_currency` (fixed after creation).
  - `portfolio_transactions`: `BUY`/`SELL`/`OPENING_POSITION`, trade date, quantity (> 0), price (≥ 0),
    fees, taxes (≥ 0), notes. Portfolio FK `CASCADE`, instrument FK `RESTRICT`.
  - `instrument_prices`: price (> 0), `as_of`, `source`, note; unique `(instrument, as_of, source)`.
- **`V5__intelligence.sql`**: `impact_assessments` (portfolio snapshot `jsonb`, snapshot hash, AI status,
  model, overall summary and status, warnings) and `impact_assessment_items` (deterministic and final
  category, `ai_interpretation`, summary, evidence status, evidence `jsonb` with chunk ids and hashes).
- Holdings, valuations and allocations are never stored.

## Portfolio calculation

`HoldingCalculator` (pure) replays each instrument's transactions in (trade date, creation time, id) order:

| Transaction | Quantity | Cost basis | Realized P&L |
|---|---|---|---|
| `BUY`, `OPENING_POSITION` | `+ q` | `+ q × price + fees + taxes` | — |
| `SELL` (q ≤ held) | `− q` | `− cost × q / held` (all of it when closing) | `+ q × price − fees − taxes − removed cost` |
| `SELL` (q > held on its date) | rejected: `422 INSUFFICIENT_HOLDINGS` with `symbol`, `tradeDate`, `requested`, `available` | | |

- **Edits and deletes:** the portfolio row is locked (`SELECT … FOR UPDATE`), the change is applied, and the
  whole history is replayed. Any sell that would then exceed the holding on its date rolls the change back.
  So editing an earlier buy or moving it after a sell is rejected, and a valid edit recalculates the
  average cost of every later sell.
- **Rounding** (`Money`, HALF_UP, display only): money to the currency's minor unit (VND 0 decimals, else 2),
  unit prices and average cost to 4 decimals, percentages to 2, quantities as entered. Totals are summed
  before rounding. Stored values are never rounded.
- **Dates:** "today" is the date in `Asia/Ho_Chi_Minh`. Trade and price dates after it are rejected
  (`400 VALIDATION_FAILED`, `errors.tradeDate` / `errors.asOf`).
- **Currencies:** an instrument's currency must equal the portfolio's base currency (`422 CURRENCY_MISMATCH`);
  there is no FX conversion.
- **Corporate actions are not modelled.** Stock splits, bonus shares, rights issues and cash dividends
  must be entered manually (e.g. a zero-price `BUY` for bonus shares). Results are then approximations;
  the UI and API (`limitations`) say so.

### Valuation and allocation

- The latest price observation on or before today is used per holding. A holding is `AVAILABLE`,
  `STALE` (older than `investary.portfolio.price-stale-after-days`, default 7) or `UNAVAILABLE`.
- Portfolio status: `AVAILABLE` (all priced, none stale), `STALE` (all priced, some stale), `PARTIAL`
  (some unpriced), `UNAVAILABLE` (none priced or no holdings).
- **No fictional P&L:** a holding without a price has no market value and no unrealized P&L. Portfolio
  `totalMarketValue` / `unrealizedPnl` are only set when every holding is priced; otherwise the
  `priced*` fields and `pricedCoveragePct` describe the priced subset.
- **Allocation** is by instrument and by instrument type, tagged `COST_BASIS` (always) and `MARKET_VALUE`
  (only when fully priced, with `marketValueUnavailableReason` otherwise). No sector or ETF look-through.

## Research matching (`ResearchMatcher`)

For each holding, two Phase 3 searches run: by ticker (filter = symbol) and by name (symbol, name and
aliases of at least 4 characters; a hit is kept only if its title or text really contains one of them).
One more search with a fixed market query collects ticker-less market commentary. Each hit is classified:

- **DIRECT:** the document's ticker is the holding, its title names it, or it is the only ticker the chunk names.
- **MENTION:** the holding is one of several tickers, or only its name appears.
- **MARKET_CONTEXT:** no tickers at all, from the market query.

Hits are ordered by source date (newest first, undated last), then score. The source date is the
publication date, or the email sent date when unknown (`dateBasis`); the import date is never used.
Evidence older than `investary.intelligence.research-fresh-days` (90) or undated is flagged `outdated`.
The existing ticker disambiguation still applies (an FRT report is not matched to FPT).

## Analysis (`POST /intelligence/portfolios/{id}/analyze`, no AI)

Valuation status, concentration (cost-basis weight above `concentration-threshold-pct`, 30, as a
reference only), instrument types outside the declared preferences, holdings named in the declared
restrictions, missing profile or goals, and research coverage per holding (direct/mention counts,
newest date, no or outdated research). Observations are neutral statements, never recommendations.

## Impact assessments

1. **Deterministic baseline** per holding: direct research → `DIRECT_HOLDING`; only mentions →
   `MONITORING_ONLY`; only market context → `MARKET_CONTEXT`; nothing → `INSUFFICIENT_EVIDENCE`. Each item
   lists its evidence (stored chunk, matched kind, hash).
2. **No evidence at all** → `aiStatus = SKIPPED_NO_EVIDENCE`, the model is not called. **No chat model** →
   `NOT_CONFIGURED`, the deterministic result is saved (not a 503).
3. **AI interpretation** (one call): evidence labelled `S1…Sn` (each chunk once, size-capped), portfolio
   facts in `<portfolio_facts>`, one `<holding symbol allowed evidence>` element per holding with evidence.
   The model returns `{overall, items: [{symbol, category, summary}]}`.
4. **Validation** per item. Each failure keeps the deterministic result and adds a warning:
   - the category must be allowed for the evidence tier (direct: `DIRECT_HOLDING`, `POTENTIAL_RISK`,
     `POTENTIAL_OPPORTUNITY`, `MONITORING_ONLY`, `INSUFFICIENT_EVIDENCE`; mention: the same without
     `DIRECT_HOLDING`; market: `MARKET_CONTEXT`, `MONITORING_ONLY`, `INSUFFICIENT_EVIDENCE`);
   - the summary is checked by `CitationValidator` against **that holding's sources only**, so citing
     another holding's evidence removes the citation; with no valid citation left the AI reading is dropped;
   - unverifiable figures lower the item to `PARTIAL`;
   - unparseable output, unknown symbols and holdings the model skipped are reported.
5. **Persistence:** portfolio snapshot (holdings, quantities, cost, weights, prices), its SHA-256, AI
   status, model, items with evidence copies (chunk id + content hash).
6. **Staleness on read:** `HOLDINGS_CHANGED` when the current holdings hash differs; `EVIDENCE_CHANGED`
   when a cited chunk is gone, was reindexed with other text, or belongs to an outdated index version.
   New research published after the assessment does not mark it stale (run a new one).

## Portfolio-aware assistant (`POST /intelligence/ask`)

- `portfolioId` is required: `422 PORTFOLIO_REQUIRED` (with `portfolioCount`) otherwise. The UI pre-selects
  a portfolio only when exactly one exists, and shows which one is used.
- Retrieval: the question's own Phase 3 search plus a ticker search for each holding named in the
  question (by ticker, name or alias), or the largest holdings by cost (up to `max-holdings-in-context`).
- Prompt: system message (Vietnamese by default; three labelled parts "Dữ liệu danh mục", "Bằng chứng từ
  nguồn", "Nhận xét của trợ lý"; no advice, no price forecasts; the assistant cannot change data), then
  `<investor_profile>`, `<goals>`, `<portfolio_facts>` (all neutralised) and `<source>` elements.
- Validation: `CitationValidator` with the portfolio facts passed as known context, so restating backend
  facts is not an "uncited figure", while research claims still need valid citations.
- No evidence → the model is not called; the facts are still returned with an `INSUFFICIENT` answer.
- Response: `portfolioSummary`, `holdings`, `portfolioFacts` (exact text given to the model), `focusSymbols`,
  then `answer`, `evidenceStatus`, `citations`, `warnings`, `newestEvidenceDate`, `model`, `disclaimer`.

## REST API

| Method | Path | Result |
|---|---|---|
| `GET`/`PUT` | `/api/v1/investor/profile` | profile (`configured: false` until saved); `PUT` replaces all fields |
| `GET`/`POST` | `/api/v1/investor/goals` | goals by priority, date, name · `201` |
| `GET`/`PUT`/`DELETE` | `/api/v1/investor/goals/{id}` | `404 GOAL_NOT_FOUND` |
| `GET`/`POST` | `/api/v1/instruments` (`?q=`) | `409 DUPLICATE_INSTRUMENT` |
| `GET`/`PUT`/`DELETE` | `/api/v1/instruments/{id}` | `409 INSTRUMENT_IN_USE` when it has transactions |
| `GET`/`POST` | `/api/v1/instruments/{id}/prices` | `{price, asOf, source, note}` · `409 DUPLICATE_PRICE` |
| `DELETE` | `/api/v1/instruments/{id}/prices/{priceId}` | `404 PRICE_NOT_FOUND` |
| `GET`/`POST` | `/api/v1/portfolios` | `409 DUPLICATE_PORTFOLIO` |
| `GET`/`PUT`/`DELETE` | `/api/v1/portfolios/{id}` | rename/description; delete cascades transactions and assessments |
| `GET` | `/api/v1/portfolios/{id}/summary` · `/holdings` · `/allocation` | computed on each request |
| `GET`/`POST` | `/api/v1/portfolios/{id}/transactions` | paged (default 50, newest first) · `422 INSUFFICIENT_HOLDINGS` / `CURRENCY_MISMATCH` |
| `PUT`/`DELETE` | `/api/v1/portfolios/{id}/transactions/{transactionId}` | revalidated by full replay |
| `POST` | `/api/v1/intelligence/portfolios/{id}/analyze` | deterministic analysis |
| `POST` | `/api/v1/intelligence/portfolios/{id}/relevant-research` | `{brokerage?, from?, to?}` → direct, mentions, market context |
| `POST`/`GET` | `/api/v1/intelligence/portfolios/{id}/impact-assessments` | create (`201`) / list (last 20, with stale flags) |
| `GET`/`DELETE` | `/api/v1/intelligence/impact-assessments/{id}` | `404 ASSESSMENT_NOT_FOUND` |
| `POST` | `/api/v1/intelligence/ask` | `{question, portfolioId, maxSources?}` |

## Frontend

- **`/portfolios`:** list and create.
- **`/portfolios/:id`:**
  - overview cards with a valuation badge; market value and unrealized P&L show "Not available"
    unless every holding is priced, plus the priced subset;
  - limitations;
  - allocation bars labelled cost basis vs market value;
  - holdings table with price, date, source and staleness, and an "Add price" form (source required);
  - transaction form (buy, sell, opening position, inline "New instrument", client checks mirroring the
    backend, backend errors shown) and a transaction table with edit/delete;
  - a research-insights panel: relevant research per holding with links to `/documents/:id` /
    `/emails/:id`, impact assessments with stale badges, AI items labelled "AI".
- **`/profile`:** profile form and goals CRUD.
- **Assistant:** "General research" / "Portfolio-aware" selector. Portfolio mode hides the research
  filters, needs an explicit portfolio when there are several, and shows the portfolio facts in a
  collapsible block separate from the cited answer.

## Testing

All tests use synthetic data; no test calls a real AI provider (`FakeAiConfiguration`, `ScriptedChatModel`).

| Area | Suites |
|---|---|
| Calculation | `HoldingCalculatorTest` (weighted average with fees/taxes, opening position, partial/full sell, residual cost, oversell, sells checked on their own date, same-day order, edit/delete replay, reopen, fractional units, rounding), `PortfolioValuatorTest` (no prices, partial, complete, stale, closed positions, empty) |
| Matching | `ResearchMatcherTest` (direct vs mention vs market context, alias/name matches, publication-date ordering and freshness, filters, short names) |
| AI | `ImpactAssessmentServiceTest` (no evidence → no call, not configured, valid interpretation with stored-chunk citations, disallowed categories, another holding's citations, invented figures, injection stays in `<source>`, unparseable output, snapshot hash), `PortfolioAssistantServiceTest` (portfolio required, not configured, facts restatable, invented figures flagged, no evidence → no call, focus by ticker/alias/size, read-only interaction) |
| Web | `InvestorControllerTest`, `PortfolioControllerTest`, `IntelligenceControllerTest` |
| Integration | `PortfolioIntegrationTest` (valuation flow, rollback of invalid edits/deletes, duplicates, currency, future dates, profile/goals), `IntelligenceIntegrationTest` (indexed synthetic corpus: relevant research incl. FPT/FRT, analysis, persisted assessment, stale after a new transaction and after deleting evidence, ask), `ArchitectureTest` (new modules, read-only rule) |
| Frontend | `portfolio-dashboard`, `transaction-form`, `investor-profile`, `research-assistant` (mode selector), `intelligence-api.service` specs |

## Known limitations

- **No corporate actions** (splits, bonus shares, rights, dividends), no cash balance, no FX, no lots
  (FIFO/specific identification), no tax reporting.
- **Manual prices only;** the valuation is as fresh as the last price you entered.
- **No instrument master data:** symbols, names and types are what the user enters; matching depends on them.
- **Matching is heuristic:** 3-character ticker detection from Phase 3, and name matching needs a name
  or alias of at least 4 characters. A long newsletter naming a holding counts as a mention.
- **AI validation** checks citations, figures and word overlap. It cannot prove that a cited passage
  semantically supports "risk" or "opportunity"; the category is constrained, not verified.
- **Staleness** detects changed holdings and changed or removed evidence, not newly published research.
- **Real-model behaviour was not evaluated:** all AI paths are verified with the scripted model only.
- The transactions table shows the newest 50 transactions (the API is paged).
