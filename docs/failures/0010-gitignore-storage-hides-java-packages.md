# 0010 — `.gitignore` rule `storage/` silently dropped the Java `storage` packages

## What happened

`.gitignore` ignores `storage/` so uploaded PDFs and emails are never committed. The pattern has no
leading slash, so it also matched the source packages `common/storage`, `document/storage` and
`email/storage`. Those classes (`StorageException`, `LocalFileStore`, `DocumentStorage`, `EmailStorage`
and their local implementations, plus their tests) were never committed. Builds kept working in the
original working copy until the files were gone; after that every checkout failed with
`package com.investary.common.storage does not exist`. The classes had to be rewritten from their call sites.

## Fix

`.gitignore` now re-includes `backend/src/main/java/**/storage/` and `backend/src/test/java/**/storage/`,
and the storage classes were recreated and committed.

## Prevention

- Ignore data folders with an anchored path (`/storage/`) or add explicit `!` exceptions for source trees.
- `scripts/check-harness.mjs` fails when any file under `backend/src` or `frontend/src` is ignored by git.

## Detection

- `node scripts/check-harness.mjs` reports `[ignored-sources]`.
- `git ls-files -o -i --exclude-standard -- backend/src frontend/src` lists ignored source files.
- A fresh clone fails to compile with "package ... does not exist".
