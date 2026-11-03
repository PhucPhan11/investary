# 0009 — "Today" for trades and prices is the Vietnamese market date, not the JVM date

## What happened

The first Phase 4 draft validated trade and price dates with `@PastOrPresent`. That annotation uses the
JVM default time zone, while valuation ("prices on or before today") uses `Asia/Ho_Chi_Minh`. Between
17:00 and 24:00 UTC a UTC JVM would reject a trade entered for the current Vietnamese date, and tests
that used "today" would fail depending on the time they ran.

## Fix

The check moved into `PortfolioService` and `InstrumentService`, which compare with
`LocalDate.now(clock.withZone(PortfolioQueryService.MARKET_ZONE))` and report a normal
`VALIDATION_FAILED` problem with `errors.tradeDate` / `errors.asOf`. The frontend computes the same date
with `marketToday()` (`Intl.DateTimeFormat` with `timeZone: 'Asia/Ho_Chi_Minh'`).

## Prevention

- Never use `@PastOrPresent` / `@FutureOrPresent` for business dates in portfolios; use the injected
  `Clock` with the market zone.
- Frontend date defaults and `max` attributes use `marketToday()`, never `new Date().toISOString()`.

## Detection

- `PortfolioIntegrationTest.duplicatesAndCurrencyMismatchesAreRejected` posts a trade two days ahead and
  expects `errors.tradeDate`; `transaction-form.spec.ts` checks `marketToday` across the UTC day boundary.
