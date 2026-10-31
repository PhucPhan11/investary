@AGENTS.md

## Claude Code notes

- The user writes in Vietnamese or English; answer in the language of their message. Code, comments,
  commit messages and repository docs stay in English.
- Folder-specific guidance is in `backend/CLAUDE.md` and `frontend/CLAUDE.md`. It loads when you work there.
- Prefer the built-in browser pane to check the UI at http://localhost:4200 (proxied to the backend on
  8080). It blocks iframes; see `docs/failures/0006-embedded-browser-iframe.md`.
- Long-running servers (`spring-boot:run`, `npm start`) should run in the background, and must be
  stopped when you are done. On Windows the Java process can outlive the task, so check port 8080.
- Real sample emails the user shares (e.g. in `Downloads`) may be read locally for analysis. Never copy
  them into the repo, never quote personal data from them, and delete any scratch copies afterwards.
