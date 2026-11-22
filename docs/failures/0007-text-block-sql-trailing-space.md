# Java text-block SQL losing trailing spaces

## Summary

`KnowledgeIndexQueue.requeue` built its SQL as a text block ending in `where ` plus a concatenated
condition. Java strips trailing spaces in text blocks, so the statement became `wherestatus <> …`. The
first fix attempt (`where\s`, written through a shell `sed`) produced `wheres…`. Both failed with
`BadSqlGrammarException`, which made reindexing unusable. Integration tests found both.

The leading side bit too (Phase 6 / M2): `NoteQueue` built `"select " + COLUMNS + """` followed by a
block starting with `from …`. Indentation stripping removed the space in front of `from`, so the statement
became `… completed_atfrom notebook_notes …` (`column "id" does not exist`). `NotebookIntegrationTest` found it.

## Root Cause

Text blocks remove incidental trailing whitespace on every line, and the common leading indentation, so the
first word of a block concatenated after another string has no space before it. A keyword at the end of a text block,
followed by `+ condition`, loses its separating space. Shell escaping then added a second mistake.

## Prevention

- Never start a text block that is concatenated after another string without an explicit `" " +` in between.
- Never end a text block with a keyword that is followed by concatenation. Concatenate with an explicit
  space (`""" + " " + condition`), or keep the whole statement in the block and use named parameters.
- Do not edit Java string escapes through `sed`; use an editor or the Edit tool.
- Rule recorded in `backend/CLAUDE.md`.

## Detection

`KnowledgeBaseIntegrationTest` (`reindexingUnchangedSourcesIsIdempotentAndCallsNoModel`,
`staleIndexVersionIsNotSearchedUntilExplicitlyReindexed`) runs every reindex scope against PostgreSQL.
A malformed statement fails `./mvnw verify`.

## Evidence

- Phase 6 / M2: `bad SQL grammar [select id, … completed_atfrom notebook_notes …]`, fixed in
  `backend/src/main/java/com/investary/notebook/persistence/NoteQueue.java`.

- Error: `bad SQL grammar [... wherestatus <> 'PROCESSING']`, then `wheresstatus`.
- Fixed in `backend/src/main/java/com/investary/knowledge/index/KnowledgeIndexQueue.java`.
