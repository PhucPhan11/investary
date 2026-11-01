# Shell heredoc dropped Java regex escapes

## Summary

During Phase 5 / Milestone 3, `common/error/ErrorSummaries.java` was created with a Bash heredoc. The
string literals `"(?i)\\b…\\S+"` and `"\\s+"` arrived as `"\b…\S+"` and `"\s+"`. The first did not compile
(`illegal escape character`); the second compiled, because `\s` is a valid Java escape for a single space,
and silently stopped collapsing line breaks. `WorkflowPoliciesTest.summariesNeverContainUrlsOrLineBreaksAndAreBounded`
caught it in `./mvnw verify`.

## Root Cause

On this machine the Git Bash heredoc path through the tool layer does not preserve doubled backslashes
reliably, even with a quoted delimiter (`<<'EOF'`). Java source with regexes is especially exposed, and
Java 15+ accepts `\s` as an escape, so one of the two damaged literals was not a compile error.

## Prevention

- Write Java (and any file with backslash escapes) with the Write/Edit tools, not shell heredocs, `sed` or
  `printf`. Shell is fine for files without backslashes.
- After generating a file through a shell, grep it for single-backslash escapes (`"\s`, `"\b`, `"\d`) before
  compiling.
- Related: [0007](0007-text-block-sql-trailing-space.md) (escapes damaged by `sed`).

## Detection

- Compilation fails for most damaged escapes (`illegal escape character`).
- `\s` and `\b`-style damage that still compiles is covered by behaviour tests of the affected code, here
  `WorkflowPoliciesTest` (summaries contain no line breaks).

## Evidence

- `ErrorSummaries.java:12: illegal escape character`, then
  `WorkflowPoliciesTest ... Expecting actual: "Failed [url]\nat line 3 ..." not to contain "\n"`.
- Fixed in `backend/src/main/java/com/investary/common/error/ErrorSummaries.java`.
