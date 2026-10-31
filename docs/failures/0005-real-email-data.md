# Real brokerage emails contain personal data

## Summary

Real `.eml` exports contain the user's e-mail address, tracking identifiers and the full message
headers. In Phase 2 the parser and classifier were tuned against a real TCBS newsletter from the
user's `Downloads` folder. Committing it, or quoting from it, would leak personal data.

## Root Cause

Real samples are the best evidence for parser rules, so it is tempting to copy them into test resources.

## Prevention

- Fixtures are synthetic: `backend/src/test/resources/eml/tcbs-style-newsletter.eml` copies the
  structure (SES-wrapped links, Vietnamese/English sections, a recommendation, promotions) with
  invented text and `investor@example.com`. Other messages are built in code with `TestEmails`.
- `.gitignore` ignores `*.eml` (and `*.pdf`, `infrastructure/.env`), except that fixture folder.
- Real samples may be read in place for analysis. Scratch copies are deleted afterwards, and personal
  fields are redacted in any output.
- Manual import of real emails happens against a storage root outside the repo, and the record is
  deleted through the API after checking.

## Detection

`node scripts/check-harness.mjs` fails if git tracks:
- an `.eml` outside `backend/src/test/resources/eml/`
- any `.pdf`
- an `.env` file

## Evidence

Phase 2 session, 2026-10-29: the real sample was analysed with redacted output, imported once for
verification, then deleted via `DELETE /api/v1/emails/{id}`, and the scratch copies were removed.
