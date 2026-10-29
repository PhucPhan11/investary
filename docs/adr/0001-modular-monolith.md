# ADR 0001 — Modular monolith

- Status: accepted
- Date: 2026-10-29

## Context

Investary is a personal tool built by a small team (one developer). Planned capabilities — document
management, research analysis, AI/RAG and portfolio tracking — share data heavily (AI answers cite
documents; decisions reference research and holdings).

## Decision

Build one Spring Boot application organised by business capability (`com.investary.<module>`), with
layered packages inside each module and a single PostgreSQL database. Frontend and backend live in one
Git repository.

## Consequences

- One build, one deployment, local transactions across features, simple debugging.
- Module boundaries are enforced by convention (package rules in `docs/architecture.md`), not by the
  network. If they need stronger enforcement later, Spring Modulith or ArchUnit tests can be added.
- A module can still be extracted into a service later (e.g. heavy AI processing) because modules
  communicate through application services and IDs.
