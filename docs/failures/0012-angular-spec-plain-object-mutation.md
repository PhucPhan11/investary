# 0012 — Angular specs that mutate plain objects do not re-render

## What happened

While writing the Milestone 4 specs, several tests set a form draft directly
(`component.draft.senderDomains = 'gmail.com'`) and then called `fixture.detectChanges()`. The template did not
change: the broad-rule warning stayed hidden, and the Save button stayed disabled because `changed()` was not
re-evaluated. The tests failed although the page works in the browser. A second problem: a spec turned on
`vi.useFakeTimers()` before `await fixture.whenStable()`; that test hung, and the fake timers leaked into the next
tests, which then timed out.

## Cause

The app is zoneless and the components are checked only when Angular marks them dirty (a template event, a signal
change or an input change). Assigning to a plain field from test code marks nothing. In the browser the same field
changes through `ngModel` input events, which do mark the component.

## Prevention

- In specs, change form values the way a user does: set the element's `value` and dispatch `input` (or `change`),
  then call `fixture.detectChanges()`. Or change a signal.
- Keep state the template must react to outside events in signals (`errors`, `saving`, `sections`).
- Turn on fake timers only after `await fixture.whenStable()`, and reset them in `afterEach`.

## Detection

- A spec that sets a plain field and expects new DOM fails with stale content (for example a disabled button or
  missing text), while the same flow works in the browser.
- A test that times out at exactly 5000 ms, followed by more timeouts in the same file, points to fake timers that
  were active during `whenStable()`.
