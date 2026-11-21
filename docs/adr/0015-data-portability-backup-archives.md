# ADR 0015 — Move user data between devices as backup archives, never through git

## Status

Accepted.

## Context

The user imports real brokerage emails and downloads their linked reports, and wants the same data on other devices.
The data lives in PostgreSQL (Docker volume) and two file stores (`investary.storage.root`,
`investary.email.storage.root`). Committing it to the repository looks convenient, but:

- the GitHub repository is **public**;
- real emails contain the user's address, tracking identifiers and headers (`docs/failures/0005-real-email-data.md`);
- downloaded PDFs are the brokerage's copyrighted research;
- git handles ~100 MB of changing binaries poorly, and `.gitignore` plus `scripts/check-harness.mjs` already forbid
  tracked `.eml` and `.pdf` files.

## Decision

- Two dependency-free Node scripts, committed with the code:
  - `scripts/backup-data.mjs` writes one `.tar.gz`: `pg_dump -Fc` of the whole database (schema, Flyway history,
    data), copies of both file stores, and `manifest.json` (counts, schema version, dump SHA-256). It refuses to write
    inside the repository.
  - `scripts/restore-data.mjs` verifies the archive, saves the current data as a pre-restore archive, then replaces
    the database (`pg_restore --clean --if-exists --single-transaction`) and both folders, and compares the restored
    counts with the manifest. It needs `--yes` and a stopped backend.
- The archive is moved privately (cloud drive, USB). `.gitignore` ignores backups and the harness fails on a tracked
  `*.dump` or `investary-backup-*` file.
- The database stores relative storage keys, so files can be restored under another home folder or storage root.

## Alternatives considered

- **Commit the data to this repository:** publishes personal data and third-party reports.
- **Private data repository:** still git for large binaries, and a second place where personal data lives.
- **Synced storage folders (OneDrive) plus a shared database:** two devices writing the same files and an out-of-date
  database are easy to get wrong; a single archive is explicit.
- **Export/import API in the application:** more code and an endpoint that streams all personal data; unnecessary for
  a local single-user app with Docker access.

## Consequences

- Moving data is a manual step and there is no merge: the last restore wins.
- Restoring into older code than the backup is not supported (Flyway would find unknown migrations); newer code
  migrates forward.
- Gmail's encrypted refresh token only decrypts with the same `INVESTARY_GMAIL_TOKEN_KEY`; otherwise reconnect.

## Amendment (2026-11-21): data folder inside the project

The user wants all project data in the project folder. In the dev profile the file stores and the download temp folder
default to `data/documents`, `data/emails` and `data/tmp` (relative to `backend/`, the working directory of
`spring-boot:run`), and backups default to `data/backups`. `data/` is git-ignored and in `.graphifyignore`; the
harness fails on any tracked file under it. The backup script may write inside the repository only into a git-ignored
folder. PostgreSQL data stays in the Docker volume: bind-mounting it into a Windows folder is slow and prone to
permission errors, and the backup archive is the portable copy. Non-dev runs keep the `~/.investary` defaults.
