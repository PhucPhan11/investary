# ADR 0007 — Portfolio accounting: weighted-average cost, replayed from transactions

Status: accepted (Phase 4, 2026-10-30)

## Context

Investary needs holdings, cost, realized and unrealized results for a personal Vietnamese-market
portfolio. There is no broker sync and no market data. Errors in money arithmetic are worse than missing
features, and the user must be able to correct past transactions.

## Decision

- **Transactions are the source of truth.** Holdings, valuations and allocations are computed on every
  read by a pure `HoldingCalculator` and `PortfolioValuator`; nothing derived is stored.
- **Weighted-average cost** (the usual method for Vietnamese retail reporting). Purchase fees and taxes
  are part of the cost; sell fees and taxes reduce proceeds. A sell removes `cost × sold / held` (the
  whole cost when it closes the position, so no residue remains).
- **Chronological replay** in (trade date, creation time, id) order. Every create, edit or delete locks
  the portfolio row, applies the change and replays the full history; a sell larger than the holding
  on its own date rolls the change back (`INSUFFICIENT_HOLDINGS`).
- **BigDecimal with `MathContext.DECIMAL128`**, no intermediate rounding. Display rounding is HALF_UP and
  applied once: currency minor units (VND 0 decimals), 4 decimals for unit prices, 2 for percentages.
- **Prices are manual observations with a source and a date.** Unrealized results exist only for priced
  holdings; portfolio totals only when every holding is priced. Cost-basis and market-value allocations
  are labelled separately.
- **Single currency per portfolio;** no FX conversion.

## Consequences

- Corrections are simple and safe: edit any transaction, everything after it is recomputed.
- Reads cost O(transactions) per request, which is negligible at personal scale.
- Corporate actions are not modelled; the user enters adjustments manually and the API states this
  limitation. Lots (FIFO) and tax reporting would need a different model and a new ADR.
