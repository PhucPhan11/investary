# Email HTML preview looks blank in Claude's browser pane

## Summary

While verifying the email detail page in the Claude desktop app's built-in browser pane, the
"Safe HTML preview" iframe stayed blank. This looked like a bug in the preview (CSP, sandbox or
`frame-ancestors`).

## Root Cause

The embedded browser pane blocks sub-frame loads: the request fails with `net::ERR_BLOCKED_BY_CLIENT`
before reaching the app. The same URL loads normally as a top-level page, and the app's CSP and
sandbox were correct.

## Prevention

- Do not "fix" the CSP or relax the iframe `sandbox` because of a blank frame in the pane.
- Verify the preview by opening `/api/v1/emails/{id}/html` directly, and check the security headers
  with `curl -D -`.

## Detection

Manual review point: if the preview is blank, check the network log. `ERR_BLOCKED_BY_CLIENT` means the
pane blocked it, not the app. Automated coverage of the headers is in `EmailControllerTest`
(`htmlPreviewIsServedWithStrictSecurityHeaders`) and `EmailIngestionIntegrationTest`.

## Evidence

Phase 2 session, 2026-10-29: the iframe request failed with `ERR_BLOCKED_BY_CLIENT`, while the direct
navigation to the same URL returned 200 and rendered the sanitized email.
