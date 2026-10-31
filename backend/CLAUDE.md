# Backend notes (Spring Boot 4.1 · Java 21)

Run everything with `./mvnw` and `JAVA_HOME` pointing at JDK 21 (see `../AGENTS.md`).

## Version-specific APIs (Boot 4 / Jackson 3 / Testcontainers 2 / Hibernate 7)

Older examples on the web will not compile here. Use:

| Need | Use |
|---|---|
| Web MVC slice test | `org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` / `AutoConfigureMockMvc` |
| Mock a bean in a slice test | `org.springframework.test.context.bean.override.mockito.MockitoBean` |
| Jackson | `tools.jackson.databind.*` (`JsonMapper`, `JsonNode`), **not** `com.fasterxml.jackson.databind` |
| Postgres container | `org.testcontainers.postgresql.PostgreSQLContainer` (non-generic) + `@ServiceConnection` |
| Unknown sort property | `org.springframework.data.core.PropertyReferenceException` |
| Flyway auto-config | needs `spring-boot-starter-flyway` (`flyway-core` alone does nothing) |
| Starters | `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`, `spring-boot-starter-data-jpa-test` |
| HTTP statuses | `HttpStatus.UNPROCESSABLE_CONTENT`, `HttpStatus.CONTENT_TOO_LARGE` |

## Persistence

- Entities use `@GeneratedValue(strategy = GenerationType.UUID)`. Source/original columns are
  `updatable = false`.
- `jsonb` columns map as `String` with `columnDefinition = "jsonb"` and `@ColumnTransformer(write = "?::jsonb")`
  (see `research/extraction/ResearchItem.java`). Serialize with the injected `JsonMapper`.
- Enum-like columns are `VARCHAR` with a `CHECK` constraint in the migration, and `@Enumerated(STRING)`.
- Cross-module references are plain `UUID` columns with database foreign keys, not JPA relations.
- Bulk JDBC work (job claims, statistics, provenance) uses `NamedParameterJdbcTemplate`.
- File side effects after a DB change go through `common.tx.AfterCommit.run(...)`.

## Tests

- Unit tests: plain JUnit 5 + Mockito + AssertJ.
- Web layer: `@WebMvcTest(Controller.class)` with `@MockitoBean` services. Assert `$.code` on problem responses.
- Integration:
  - Use `@SpringBootTest` + `@Testcontainers(disabledWithoutDocker = true)` + `@Container @ServiceConnection`.
  - Give each test class its own temp storage via `@DynamicPropertySource`.
  - `src/test/resources/application.properties` keeps storage under the temp dir and turns the
    download worker **off**.
- If a test enables `investary.download.worker.enabled=true`, annotate the class
  `@DirtiesContext(classMode = AFTER_CLASS)` (see `docs/failures/0004-integration-test-background-worker.md`).
- External HTTP is mocked with WireMock (`WireMockExtension`). Local tests must explicitly allow
  `allow-http`, `allow-private-networks`, `allow-any-port` and `additional-allowed-domains=localhost`.
- EML fixtures: `src/test/resources/eml/` (synthetic only), or build them in code with `com.investary.email.TestEmails`.
- PDF bytes for tests: `com.investary.document.TestPdfs`.
- `ArchitectureTest` enforces module boundaries. Fix the dependency; never weaken the test.

## Knowledge base / AI (Phase 3)

- Spring AI 2.0: `org.springframework.ai.embedding.EmbeddingModel`, `org.springframework.ai.chat.model.ChatModel`,
  `org.springframework.ai.retry.NonTransientAiException`. Inject models via `ObjectProvider` (they may be absent).
- Vectors are written as text with `cast(:param as vector)` (`PgVectors`); no pgvector JDBC type.
- Container tests use `com.investary.TestDatabase.container()` (pgvector image), **not** `postgres:*`.
- Knowledge tests: `@Import(FakeAiConfiguration.class)` + `@TestPropertySource(locations = "classpath:fake-ai.properties")`;
  run indexing synchronously with `KnowledgeTestSupport.indexAll(queue, processor)`; data from
  `SyntheticResearchCorpus` and `TestResearchPdfs`.
- Classes with two constructors (a test seam) need `@Autowired` on the injection constructor.
- Java text blocks strip trailing spaces: never end a text block with `where ` and concatenate a condition
  (see `docs/failures/0007-text-block-sql-trailing-space.md`).

## Portfolios and intelligence (Phase 4)

- `text[]` columns map as `String[]` with `@JdbcTypeCode(SqlTypes.ARRAY)` and `columnDefinition = "text[]"`
  (see `portfolio/domain/Instrument.java`).
- Money: `BigDecimal` with `HoldingCalculator.MC` (`DECIMAL128`); round only in `Money`. Avoid
  `BigDecimal.divide` without a `MathContext` (non-terminating expansions throw), also in tests.
- "Today" for trades and prices is the Asia/Ho_Chi_Minh date (`PortfolioQueryService.today()`); do not use
  `@PastOrPresent` for those dates, it uses the JVM zone.
- Model calls outside the knowledge assistant go through `knowledge.assistant.ChatGateway`; never call a
  `ChatModel` inside a database transaction (`ImpactAssessmentService` saves with `TransactionTemplate`).
- Intelligence tests: `IntelligenceTestData` for unit tests; `IntelligenceIntegrationTest` indexes the
  synthetic corpus and scripts the model from the prompt's `<holding>` / `<source>` elements.

## Validation gotcha

`@ModelAttribute` records stop at the first binding failure. An invalid enum means the other field
errors are not reported in the same response. This is expected; see `DocumentControllerTest`.

## Gmail integration (Phase 5 / Milestone 1)

- Module `automation`: Google HTTP clients in `automation/google/` (`GoogleHttp` = `RestClient` over the JDK client),
  token encryption and OAuth state in `automation/security/`, sync engine in `automation/application/GmailSyncRunner.java`.
- Gmail state uses JDBC stores (`GmailAccountStore`, `GmailSyncRunStore`, `GmailMessageStore`); only source rules
  are a JPA entity. Cursor and status writes of a running sync must keep the `sync_lock_token = :token` guard.
- Container tests: `automation/GmailSyncIntegrationTest.java` points `investary.gmail.*-uri` / `api-base-url` at
  WireMock. Static `WireMock.get` and `WireMock.post` clash with MockMvc's; call them as `WireMock.get(...)`.
- Tests that enable the Gmail scheduler need `@DirtiesContext`; it is off in `src/test/resources/application.properties`.
