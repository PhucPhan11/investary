# ADR 0004 — Report download policy and tracking-link unwrapping

- Status: accepted
- Date: 2026-10-29

## Context

Brokerage emails link to research PDFs instead of attaching them. Fetching those URLs means a server
on the user's machine requests URLs taken from untrusted input. That is a classic SSRF vector: it could
reach `localhost`, the LAN, cloud metadata endpoints, or rebinding DNS names. The links are also
wrapped by click trackers. In TCBS's newsletters, every link goes through
`https://<id>.r.<region>.awstrack.me/L0/<url-encoded target>/1/<message>/<signature>` (Amazon SES).

## Decision

1. **Downloads are user-initiated only.** Links are classified on import, but nothing is fetched until
   the user requests a specific link.
2. **Unwrap trackers deterministically.** When the target is embedded in the tracking URL (Amazon SES
   `L0` format), store both URLs and fetch the target directly. The original URL is kept for provenance.
   Opaque trackers are not unwrapped; their redirect chain is followed under the same policy.
3. **URL policy for the request and every redirect hop:**
   - https only
   - host must match the allowlist: configured brokerage domains (sub-domains included) plus
     explicitly configured extra domains
   - no credentials in the URL
   - standard ports
   - at most 5 redirects, followed manually
4. **Address policy at connection time.** A custom DNS resolver rejects any host whose resolved
   addresses include loopback, private, CGNAT, link-local, multicast, reserved, unique-local or
   IPv4-mapped/NAT64 forms of those. The HTTP client connects only to the addresses that were checked,
   so a rebinding answer is caught on the connection that would use it.
5. **Content policy:**
   - stream to a temp file under a size cap and a deadline
   - accept only bytes with a PDF signature
   - store through the existing document service, which deduplicates by SHA-256
6. **Reliability:** database-backed jobs with `SKIP LOCKED` claims, claim tokens, leases, and bounded
   concurrency. Transient failures retry with backoff; permanent ones fail with a readable reason. The
   same endpoint retries a failed link.

## Consequences

- Fetching the unwrapped target never pings the tracker, so no "click" is reported to the sender, and
  the tracker domain does not need to be trusted.
- New providers need configuration (brokerage domains), not code. A broker whose reports sit on a
  CDN or behind an opaque tracker needs that domain added explicitly. This is a deliberate trade-off
  of convenience for safety.
- Local testing against a mock server requires `allow-http`, `allow-private-networks` and
  `allow-any-port`. These are only enabled in tests and are documented as never-for-production.
- Reports behind logins or HTML viewers fail with a clear message instead of storing an HTML page as a "PDF".
