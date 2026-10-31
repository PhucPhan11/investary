# Module dependency cycle between email and research

## Summary

During Phase 2, `research.link.LinkClassifier` imported `email.parsing.TrackingLinkUnwrapper` to reuse
its percent-decoding helper. `email` already depended on `research`, so the modules had a cycle. A
similar cycle almost appeared when `EmailStorage` (storage layer) imported a properties class from
`email.application`.

## Root Cause

Reaching for an existing helper in another module without checking the dependency direction
`email → research → document → common`. Nothing enforced the direction automatically.

## Prevention

- Shared helpers belong in `common` (the decoder became `common.web.UrlCodec`; hashing became
  `common.util.Hashing`).
- `research` defines its own input types (`ResearchSource`) instead of consuming `email.parsing` records.
- Module-level properties classes live at the module root (`com.investary.email.EmailProperties`), not in `application`.

## Detection

`backend/src/test/java/com/investary/ArchitectureTest.java` scans `src/main` imports and fails when:
- `common` imports a feature module
- `document` imports `email` or `research`
- `research` imports `email`
- a module imports another module's `api`, `persistence` or `storage` package

## Evidence

Phase 2 session, 2026-10-29: `UrlCodec` and `Hashing` were extracted to `common`, and
`EmailProperties` was moved to the module root.
