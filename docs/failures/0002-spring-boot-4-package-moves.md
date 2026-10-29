# Spring Boot 4 / Jackson 3 / Testcontainers 2 package moves

## Summary

Code and tests written from Spring Boot 3 knowledge do not compile here, or compile but miss
auto-configuration. Examples met while building Phase 1–2:

- `@WebMvcTest` and `@AutoConfigureMockMvc` moved to `org.springframework.boot.webmvc.test.autoconfigure`.
- `PropertyReferenceException` moved to `org.springframework.data.core`.
- Jackson 3 lives under `tools.jackson.*`, so `com.fasterxml.jackson.databind.ObjectMapper` is not available.
- Testcontainers 2 has `org.testcontainers.postgresql.PostgreSQLContainer` (non-generic) and artifacts
  named `testcontainers-postgresql` / `testcontainers-junit-jupiter`.
- Flyway only auto-configures with `spring-boot-starter-flyway`; `flyway-core` alone does nothing.
- Starters are split (`spring-boot-starter-webmvc`, `…-webmvc-test`, `…-data-jpa-test`).
- `HttpStatus.UNPROCESSABLE_ENTITY` became `UNPROCESSABLE_CONTENT`.
- In HttpClient 5, `ConnectTimeoutException` is a subclass of `SocketTimeoutException`, so a
  multi-catch of both does not compile.

## Root Cause

Major-version upgrades of Spring Boot, Jackson, Testcontainers and Hibernate. Most examples online
still show the old packages.

## Prevention

- `backend/CLAUDE.md` lists the correct packages and starters.
- When unsure, check the jar instead of guessing:
  `unzip -l ~/.m2/repository/<group path>/<artifact>/<version>/*.jar | grep ClassName`.

## Detection

- `./mvnw test-compile` fails on the wrong imports.
- A missing Flyway starter shows up as Hibernate validation errors ("missing table") in
  `DocumentIntegrationTest` and `EmailIngestionIntegrationTest`.

## Evidence

Phase 1 and 2 sessions, 2026-10-29: package lookups were done with `unzip -l` on the jars. One
compile failure came from the `ConnectTimeoutException` multi-catch in `ReportDownloader`.
