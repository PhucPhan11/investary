# Frontend notes (Angular 22)

- Run `npm start` / `npm test -- --watch=false` / `npm run build` from this folder. **Do not use `npx`**;
  NVM blocks it on this machine. Run the CLI via `npm run ng -- …` or `node node_modules/@angular/cli/bin/ng.js …`.
- Dev server proxies `/api` to `http://localhost:8080` (`proxy.conf.json`). Use relative `/api/v1/...` URLs.

## Conventions

- **Components:** standalone, `inject()`, signals and `computed`, built-in control flow (`@if` / `@for`).
  The app is zoneless.
- **File names:** no `.component` suffix (`email-detail.ts`, class `EmailDetail`). Templates go in
  `.html` next to the component when longer than a few lines.
- **Layout:** `core/` holds API services, models and `error-message.ts`; `shared/` holds `Alert`,
  `EmptyState`, `Pager`, `StatusBadge` and pipes; `features/<area>/` holds the lazy-loaded routes in
  `app.routes.ts`.
- **Route params** bind to `input.required<string>()` (`withComponentInputBinding()`).
- **HttpClient** uses the default XHR backend; it reports upload progress, fetch does not.
- **Styling:** global tokens and classes in `src/styles.scss` (navy / white / green). Component style
  budgets are small, so add shared styles there. No UI libraries.
- **Email HTML:** never render it with `innerHTML` or `bypassSecurityTrustHtml`. Show the backend's
  sanitized `/api/v1/emails/{id}/html` in an `<iframe sandbox>` without `allow-scripts` / `allow-same-origin`.
- **Errors:** map HTTP errors to user text with `errorMessage()`. Show loading, empty and error states on every page.
- **AI answers** are plain text: split them with `answerSegments()` and render citation markers as buttons;
  never `innerHTML`. Citations link to `/documents/:id` or `/emails/:id`.

## Languages (ADR 0014)

- Build-time Angular i18n (@angular/localize): English source, Vietnamese in `src/locale/messages.vi.xlf` (default). `npm start`
  serves Vietnamese on 4200, `npm run start:en` English on 4201; `npm run build` builds both (dist folders browser/vi and browser/en).
- **Every UI string needs a custom id**: `i18n="@@area.key"` (plus `i18n-placeholder` / `i18n-title` /
  `i18n-aria-label` / `i18n-data-label` on attributes) in templates, `` $localize`:@@area.key:Text` `` in TypeScript
  (placeholders `${value}:name:`). Never build text from string literals in ternaries inside templates; use `@if`
  blocks with one message each, and ICU `{n, plural, =1 {…} other {…}}` for plurals.
- After adding or changing text: `npm run extract-i18n`, then add or update the `<target>` in `messages.vi.xlf` for
  every changed id. The `vi` build fails on a missing translation. Do not nest `i18n` elements.
- An `@if` block whose first child is an `<ng-container i18n>` loses the space before it; add `{{ ' ' }}` when it follows
  other text.
- Do not translate research content (email text, PDFs, research items, AI output); see `core/i18n/research-language.ts`.
- Specs run in the source locale, so they assert English text.

## Tests (Vitest via `ng test`)

- Use `provideHttpClient()`, `provideHttpClientTesting()` and `HttpTestingController`, and call
  `http.verify()` in `afterEach`.
- With `vi.useFakeTimers()`, do **not** `await fixture.whenStable()` after flushing requests; it hangs.
  Call `fixture.detectChanges()` instead and advance time with `vi.advanceTimersByTime`.
- Set component inputs with `fixture.componentRef.setInput('id', …)`.
- Components are not checked by `fixture.detectChanges()` after a test mutates a plain field; type into the input
  (`input.value = …; dispatchEvent(new Event('input'))`) or change a signal. See
  `docs/failures/0012-angular-spec-plain-object-mutation.md`.
- Spec helpers shared between specs live in `src/app/testing/` (importing another `*.spec.ts` runs its tests twice).
- Automation Center: `features/automation/` (overview, sources, jobs, job detail, settings, AI usage) under the
  `AutomationCenter` route shell; the header bell is `shared/components/notification-bell.ts`.
- Decision journal: `features/journal/` (`decision-list`, `decision-form` for `/journal/new` and `/journal/:id/edit`,
  `decision-detail`); fixtures in `testing/journal-fixtures.ts`. Every write sends the shown `version` as
  `expectedVersion`; a 409 `DECISION_CONFLICT` maps to a "reload" message.
