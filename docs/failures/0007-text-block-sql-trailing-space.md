# Java text-block SQL losing trailing spaces

## Summary

`KnowledgeIndexQueue.requeue` built its SQL as a text block ending in `where ` plus a concatenated
condition. Java strips trailing spaces in text blocks, so the statement became `wherestatus <> …`. The
first fix attempt (`where\s`, written through a shell `sed`) produced `wheres…`. Both failed with
`BadSqlGrammarException`, which made reindexing unusable. Integration tests found both.

## Root Cause

Text blocks remove incidental trailing whitespace on every line. A keyword at the end of a text block,
followed by `+ condition`, loses its separating space. Shell escaping then added a second mistake.

## Prevention

- Never end a text block with a keyword that is followed by concatenation. Concatenate with an explicit
  space (`""" + " " + condition`), or keep the whole statement in the block and use named parameters.
- Do not edit Java string escapes through `sed`; use an editor or the Edit tool.
- Rule recorded in `backend/CLAUDE.md`.

## Detection

`KnowledgeBaseIntegrationTest` (`reindexingUnchangedSourcesIsIdempotentAndCallsNoModel`,
`staleIndexVersionIsNotSearchedUntilExplicitlyReindexed`) runs every reindex scope against PostgreSQL.
A malformed statement fails `./mvnw verify`.

## Evidence

- Error: `bad SQL grammar [... wherestatus <> 'PROCESSING']`, then `wheresstatus`.
- Fixed in `backend/src/main/java/com/investary/knowledge/index/KnowledgeIndexQueue.java`.
