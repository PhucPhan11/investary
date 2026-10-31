# ADR 0005 — Spring AI for models, own pgvector schema

- Status: accepted
- Date: 2026-10-29

## Context

Phase 3 needs embeddings, a chat model and vector search. The backend runs Spring Boot 4.1.2 (Java 21,
Jackson 3) with a Flyway-owned schema and `ddl-auto=validate`. The candidates were Spring AI 2.0.2 (GA,
built for Boot 4) and LangChain4j 1.23.0. No AI framework was present yet.

The knowledge base also needs things a generic vector store does not give us:
- per-source job state (claim token, lease, retries) in the same table design as `download_jobs`;
- page and section locations for citations;
- an index version so different embedding models are never compared;
- hybrid SQL: pgvector plus full text plus ticker arrays.

## Decision

- Use **Spring AI 2.0.2** for the model abstractions only: `EmbeddingModel`, `ChatModel`, `Prompt`, and
  `JTokkitTokenCountEstimator` for chunk sizes.
  - The provider is chosen by configuration (`spring.ai.model.embedding` / `spring.ai.model.chat`).
  - Only `spring-ai-starter-model-ollama` is on the classpath; the default is `none`.
  - Business code depends on the interfaces through `ObjectProvider`, so the app starts without a provider.
- Do **not** use Spring AI's `PgVectorStore`. Own the `knowledge_sources` / `knowledge_chunks` schema in
  Flyway (`V3`) and query it with `NamedParameterJdbcTemplate`.
- Do not add LangChain4j. Two AI frameworks would duplicate configuration and model wiring for no gain.

## Consequences

- **Switching provider:** add another Spring AI starter (OpenAI, Gemini, …) and set
  `INVESTARY_AI_*_PROVIDER`. Embedding model and dimensions are part of the index version, so a switch
  makes old chunks stale until `POST /knowledge/reindex {"scope":"STALE"}`.
- **What we write ourselves:** batching, retry and dimension checks (`EmbeddingService`), and hybrid
  retrieval SQL. In exchange, schema migrations, transactions and tests stay under our control.
- **Retries:** Spring AI's own retry is limited to 2 attempts (`spring.ai.retry.max-attempts`); the
  indexing job adds slower retries.
