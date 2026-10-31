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

## Tests (Vitest via `ng test`)

- Use `provideHttpClient()`, `provideHttpClientTesting()` and `HttpTestingController`, and call
  `http.verify()` in `afterEach`.
- With `vi.useFakeTimers()`, do **not** `await fixture.whenStable()` after flushing requests; it hangs.
  Call `fixture.detectChanges()` instead and advance time with `vi.advanceTimersByTime`.
- Set component inputs with `fixture.componentRef.setInput('id', …)`.
