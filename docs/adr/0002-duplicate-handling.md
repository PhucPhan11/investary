# ADR 0002 — Duplicate document handling

- Status: accepted
- Date: 2026-10-29

## Context

Brokerage reports often arrive several times (e-mail, download portals, re-sends). Storing identical
content twice wastes space and would later produce duplicate AI context.

## Decision

- Identity of content is its **SHA-256 checksum**, enforced by a unique constraint on
  `document.sha256_checksum`.
- Uploading content that already exists is **rejected** with `409 Conflict`; the problem response
  includes `existingDocumentId` so clients can link to the existing document.
- The service checks for an existing checksum before writing the file. A concurrent upload that slips
  past that check hits the unique constraint; the stored file is removed and the client still gets 409.
- Metadata of the duplicate request is not merged into the existing document.

## Alternatives considered

- *Silently return the existing document (200)*: hides that the new metadata was ignored.
- *Allow duplicates*: simplest, but pollutes the library and future retrieval.

## Consequences

The same report with a different file name or metadata cannot be stored twice. A future "edit metadata"
feature is the intended way to correct metadata.
