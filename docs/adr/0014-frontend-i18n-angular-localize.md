# ADR 0014 — Bilingual UI (Vietnamese / English) with build-time `@angular/localize`

## Status

Accepted.

## Context

The user reads Vietnamese and English, and most brokerage research they import is Vietnamese (TCBS emails also carry an
English block). The frontend had about 35 components with English text hard-coded in templates, TypeScript label maps
and `errorMessage()`. The UI should work in both languages, with Vietnamese as the default.

Research content raises a separate question: Investary copies values from sources and never invents them (AGENTS.md).
A machine translation of a brokerage statement would be new text attributed to the brokerage.

## Decision

- **Official Angular i18n, built at compile time.** `@angular/localize` with `sourceLocale: en` (the code stays in
  English) and one target locale `vi` (`frontend/src/locale/messages.vi.xlf`, XLIFF 2). Every message has a custom,
  stable id (`i18n="@@area.key"`, `` $localize`:@@area.key:…` ``). `i18nMissingTranslation: error` makes the build
  fail on an untranslated id.
- **One build per language.** `npm run build` outputs `browser/vi` and `browser/en` (base href `/vi/`, `/en/`). The dev
  server serves one locale, so development runs Vietnamese on 4200 (`npm start`, default) and English on 4201
  (`npm run start:en`). The header switch is a plain link to the same route on the other build: the other dev
  server's origin is injected per build configuration (`define: INVESTARY_ALT_LANGUAGE_URL`); without it the
  production layout `/<lang>/…` is used.
- **Locale formatting for free.** The localized build sets `LOCALE_ID` and `<html lang>`, so the existing `DatePipe` and
  `DecimalPipe` format dates and numbers for the language.
- **Only the interface is translated.** Email text, PDFs, research items, AI answers, briefs and backend messages stay
  in their source language. Where the source itself provides a language — bilingual items in one email, or
  `structuredData.translations.<lang>` — the email page prefers the UI language (`core/i18n/research-language.ts`),
  labels a source-provided translation as such, and keeps "Show original" and "Show all languages". Investary never
  machine-translates research.
- **Backend-defined UI text** that is fixed per field (runtime settings labels and help) is translated in the frontend by
  key (`features/automation/setting-labels.ts`), falling back to the backend's English text for unknown fields.

## Alternatives considered

- **Runtime translation service** (signal-based dictionary, instant switch without reload): one build and one dev
  server, but every template binding goes through a pipe or function, there is no extraction tooling, and missing
  keys are only found at runtime. The user chose the official tooling.
- **A third-party library (ngx-translate, Transloco):** the project uses no UI libraries and avoids new dependencies
  where Angular provides the feature.
- **Translating research content with a model:** would present generated text as the brokerage's statement.

## Consequences

- Switching language reloads the page, and local development needs a second dev server to see English.
- Every new UI string needs an id and a Vietnamese target; `npm run extract-i18n` refreshes `messages.xlf` (the source
  list) and the `vi` build reports any missing translation.
- Unit tests run in the source locale, so their English text assertions keep working.
- Backend-generated sentences (notification titles, brief sections, assistant disclaimers, validation details) remain
  English until the backend localizes them.
