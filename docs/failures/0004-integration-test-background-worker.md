# Download worker keeps polling after its test container stopped

## Summary

`EmailIngestionIntegrationTest` enables the scheduled download worker. Spring caches test contexts
until the JVM exits, but Testcontainers stops each class's PostgreSQL container when the class
finishes. The cached context's worker kept polling a dead database:
- logs filled with `Connection refused` / `EOFException`
- Hikari waited 30 s per attempt
- later test classes slowed down

## Root Cause

A context-cached background poller plus a per-class container lifecycle.

## Prevention

- Download workers are off in tests by default (`backend/src/test/resources/application.properties`).
- Any test class that sets `investary.download.worker.enabled=true` must be annotated
  `@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)`.

## Detection

- `./mvnw verify` output shows `Connection to localhost:<port> refused` or `HikariPool … request timed
  out` after an integration test class finished.
- Manual review point: when adding `worker.enabled=true` to a test, check for `@DirtiesContext`.

## Evidence

Phase 2 session, 2026-10-29: `DownloadJobQueueTest` took about 27 s instead of about 10 s until
`@DirtiesContext` was added to `EmailIngestionIntegrationTest`.
