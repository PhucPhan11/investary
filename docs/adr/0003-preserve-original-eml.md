# ADR 0003 — Preserve original EML files

- Status: accepted
- Date: 2026-10-29

## Context

Brokerage emails are the provenance of most research in Investary: they carry report links, market
commentary and explicit recommendations. Phase 2 has to store them in a way that supports later
reprocessing (better parsers, PDF text extraction, AI summaries with citations) and auditing
("where did this recommendation come from?").

Options considered:

1. Keep the original `.eml` file, plus parsed metadata and derived data in PostgreSQL.
2. Convert every email to PDF and store it in the document library.
3. Store only parsed fields (subject, text, links) in the database.

## Decision

Keep the **original EML file, byte for byte**, in a dedicated email store
(`<email-root>/yyyy/MM/<uuid>.eml`). The database holds immutable header metadata, the SHA-256 of the
file, and *derived* data (normalized text, original HTML, links, research items, attachment metadata).

## Consequences

- Nothing is lost:
  - headers (Message-ID, routing, dates)
  - every MIME part, attachments included
  - original HTML, charsets and encodings
  - tracking-wrapped URLs
- Derived data can be regenerated when parsing or classification rules improve, without asking the
  user to re-import anything.
- Converting to PDF was rejected. It loses links (or keeps them only as rendered text), drops
  structure that the extraction rules rely on, and makes the derived data impossible to re-derive.
- Keeping only parsed fields was rejected for the same reprocessing and audit reasons.
- Storage use is modest: typical newsletters are 50–200 KB.
- Raw EML is not stored in PostgreSQL; this keeps the database small and backups fast. Original HTML
  is stored in the database because it is needed for the preview and is small.
- Duplicate detection uses the file checksum. Message-ID is kept as metadata only, because different
  copies of "the same" email (forwarded or re-exported) legitimately differ in bytes.
