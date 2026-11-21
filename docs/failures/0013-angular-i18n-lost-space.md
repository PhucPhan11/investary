# 0013 — Translated `ng-container` messages lose the space between them

## What happened

While marking the UI for `@angular/localize` (ADR 0014), a ternary such as
`{{ j.chunkCount }} passages @if (j.pageCount) { from {{ j.pageCount }} pages }` was split into two translatable
messages, each in an `<ng-container i18n>`. The page then showed "5 searchable passagesfrom 2 pages": the space between
the messages disappeared. `IndexStatus`'s spec caught it; the same pattern also joined "(Asia/Ho_Chi_Minh)" and
"· partial period" on the brief page.

## Cause

Angular removes text nodes that contain only whitespace (`preserveWhitespaces: false`). The newline and indentation
between the end of one block and an `@if { <ng-container i18n> … }` that starts with an element is such a node, so no
space is rendered. Before the change the words were plain text in one node and the space survived.

## Prevention

- Keep a phrase that reads as one sentence in one message (use ICU `plural` / `select` instead of splitting it).
- When an `@if` block starting with `<ng-container i18n>` follows other text on the same line, add an explicit space:
  `@if (x) { {{ ' ' }}<ng-container i18n="@@…">…</ng-container> }`, or start the block with a visible separator
  (`· `), which is not whitespace-only.

## Detection

- Text assertions with `\s+` between two parts (`/passages\s+from/`) fail with the words glued together.
- In the browser, look for words or a closing parenthesis directly followed by the next word after a conditional
  part, in both the Vietnamese and the English build.
