# Phase 2 — Email Intelligence & Research Ingestion: implementation plan

Status: implemented (see the README for usage, [architecture.md](architecture.md) for structure,
ADR [0003](adr/0003-preserve-original-eml.md) and [0004](adr/0004-report-download-policy.md) for the key
decisions). This file records the plan and the decisions behind it.

Deviations from the original brief:
- **Storage layout.** Documents keep the Phase 1 layout (`<uuid>.pdf`), not `reports/{sha256}/original.pdf`.
  This keeps existing files valid without a migration, and SHA-256 uniqueness is enforced in the database.
- **Extra tables.** `source_attachments` and `download_jobs` were added. `source_links` also stores the
  unwrapped target URL, section, language, classification rule, ticker hint and source brokerage.

## Starting point

Phase 1 delivered a Spring Boot 4.1 / Java 21 modular monolith (`document`, `common`), Flyway `V1`
(`document` table), local PDF storage behind `DocumentStorage`, RFC 9457 errors and an Angular 22 UI.

## Findings from a real brokerage email

A real TCBS newsletter ("Cơ hội đầu tư | 28/10/2026") was inspected locally and **not committed**.
Synthetic fixtures reproduce its structure:

- `multipart/mixed` with a single quoted-printable `text/html` part; no text/plain, no attachments;
  RFC 2047 encoded Vietnamese subject; built with Mailchimp, sent through Amazon SES.
- Every link is wrapped by the SES click tracker
  `https://<id>.r.<region>.awstrack.me/L0/<url-encoded target>/1/<message>/<signature>`.
- Targets: report PDFs (`www.tcbs.com.vn/wp-content/uploads/...pdf`), one-click ticker PDFs
  (`static.tcbs.com.vn/oneclick/VIC.pdf`, `VIC_EN.pdf`), promotion pages (`/ca-nhan/uu-dai/...`),
  report listing pages, social links, subscribe/unsubscribe.
- A Vietnamese block followed by an English copy (`#eng` anchor). Bold numbered headings:
  research publications, market news (general market, macro, sectors, corporate news),
  stock recommendation (`SCS - Chờ mua - ...` / `SCS - Awaiting Buy - ...`), promotions, footer.
- The preheader is padded with invisible characters (U+034F, U+200C, U+00AD).

## Decisions

| # | Decision | Why |
|---|----------|-----|
| 1 | Keep the original tracking URL **and** a deterministically unwrapped target URL; download the target | No click is reported to the tracker; the tracker never needs allowlisting |
| 2 | Shared `common.storage.LocalFileStore`; documents keep `<uuid>.pdf` in `investary.storage.root`; emails go to `investary.email.storage.root` as `yyyy/MM/<uuid>.eml` | Existing PDFs stay valid without a data migration; content-addressing is already enforced by the unique checksum |
| 3 | Downloaded PDFs and PDF attachments go through `DocumentService.upload`; a duplicate links to the existing document | One storage implementation; the same report referenced by several emails is stored once |
| 4 | Database-backed download jobs (`FOR UPDATE SKIP LOCKED`, claim token, lease, bounded worker pool, retries with backoff) | Reliable background work without a message broker |
| 5 | Downloader on Apache HttpClient 5 with an address-validating DNS resolver, manual redirect validation, https-only, domain allowlist, timeouts, size caps, PDF signature check | SSRF and DNS-rebinding protection |
| 6 | Server-side jsoup sanitizing + CSP header; Angular shows HTML only inside a sandboxed iframe; plain text is the default | Email HTML is untrusted |
| 7 | Deleting an email never deletes PDFs; deleting a PDF resets links via a `DocumentDeletedEvent` | Shared reports must not disappear with one email |
| 8 | `common.error.ApiException` base class | Error handler no longer depends on feature modules |
| 9 | Email duplicates by SHA-256 of the raw file (409); Message-ID indexed, not unique | Message-ID is not globally reliable |
| 10 | Rule-based link classification and research-item extraction (`RULES_V1`), bilingual keyword dictionaries, provenance stored | Deterministic and explainable; no LLM in Phase 2 |

## Schema (`V2__email_ingestion.sql`, additive)

`source_messages`, `source_attachments`, `source_links`, `message_documents`, `research_items`,
`download_jobs`. The Phase 1 `document` table is unchanged.

## Work breakdown

1. Common refactors, dependencies (Angus Mail, jsoup, HttpClient 5; WireMock + Awaitility for tests), V2 migration.
2. EML import: validation, SHA-256 duplicate detection, storage, parsing, persistence, listing, deletion.
3. HTML normalization and sanitizing, link extraction, tracker unwrapping, classification, detail endpoints.
4. Secure downloader, job queue and worker, document associations, retry.
5. Deterministic research items.
6. Angular: email inbox, email details, document detail page, shared pager and status badges.
7. Tests at every step; documentation (README, architecture, ADR 0003, ADR 0004).
