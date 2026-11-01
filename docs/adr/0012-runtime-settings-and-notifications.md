# ADR 0012 — Runtime setting overrides and in-app notifications

## Status

Accepted (Phase 5 / Milestone 4).

## Context

The Automation Center must let the user change Gmail polling, processing limits, the brief schedule and AI
limits without editing `application.yml` and restarting. Every setting was an immutable
`@ConfigurationProperties` record. Milestone 4 also needs persistent, de-duplicated in-app notifications for
events raised in `automation` (sync, workflows) and `intelligence` (assessments, briefs).

## Decision

- **Settings:** `application.yml` and environment variables stay the defaults. One table, `app_settings`
  (one row per section: a JSON object of overrides and a version), holds the user's overrides; a saved value wins
  until it is reset. Access goes through `common.settings.RuntimeSettings` and `SettingsStore` (JDBC, optimistic
  version check per section). Each owning module declares its fields (`SettingsContributor`) and exposes an
  effective-settings class that merges its defaults with its overrides; bounds are validated on save:
  `automation` (`gmail.*`, `processing.*`), `intelligence` (`brief.*`), `knowledge` (`ai.*`). Values are read
  per use (short cache, invalidated on write), so changes apply without a restart, except the worker pool size,
  which is read once at start-up and is labelled "applies after restart". Kill switches
  (`*.worker.enabled`, `gmail.sync.scheduler-enabled`, `workflow.enabled`) remain configuration-only and are shown
  read-only; credentials never appear in a settings section.
- **Notifications:** stored in `notifications` and owned by `automation`, which may already depend on
  `intelligence` and is imported by nothing. `intelligence` publishes plain events after commit
  (`BriefGeneratedEvent`, `BriefJobFailedEvent`, `ImpactAssessmentCreatedEvent`); `automation` listens and
  applies deterministic rules. Every notification has a unique `dedup_key`, inserted with
  `on conflict do nothing`. No model call decides whether to notify.
- **AI usage:** `knowledge` records provider calls in `ai_usage_daily` (date, operation, purpose, model,
  requests, failures, provider-reported tokens) after each call, outside any transaction around the call.

## Alternatives considered

- **Spring Cloud Config / `@RefreshScope`:** new infrastructure and a refresh model that rebuilds beans; ruled out.
- **One settings table per module:** more migrations and duplicated code for the same key/value need.
- **A `notification` module:** it would have to import `automation` events (nothing may) or move sync and
  workflow events into a shared module; `automation` already owns the local-only operational surface.
- **Micrometer metrics for usage:** in-memory, lost on restart, and not queryable by day without a metrics store.

## Consequences

- A setting has two sources; the UI shows the default and whether it is overridden, and "Reset" removes the override.
- Usage counters start when Milestone 4 is installed; earlier activity is only visible through briefs and
  assessments.
- Notification rules are code, versioned with the application; changing them does not rewrite old notifications.
