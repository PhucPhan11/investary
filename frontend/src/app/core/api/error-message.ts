import { HttpErrorResponse } from '@angular/common/http';

import { ProblemDetail } from '../models/document.model';

/**
 * Turns an HTTP error into a short, user-facing message. Known cases are translated; otherwise the server's
 * `detail` (English) is shown as sent.
 */
export function errorMessage(
  error: unknown,
  fallback = $localize`:@@error.generic:Something went wrong. Please try again.`,
): string {
  if (!(error instanceof HttpErrorResponse)) {
    return fallback;
  }
  if (error.status === 0) {
    return $localize`:@@error.unreachable:Cannot reach the Investary server. Is the backend running?`;
  }
  const problem = (typeof error.error === 'object' ? error.error : null) as ProblemDetail | null;
  if (error.status === 413) {
    return $localize`:@@error.tooLarge:The file is larger than the allowed upload size.`;
  }
  if (problem?.code === 'DUPLICATE_DOCUMENT') {
    return $localize`:@@error.DUPLICATE_DOCUMENT:This document has already been uploaded.`;
  }
  if (problem?.code === 'DUPLICATE_EMAIL') {
    return $localize`:@@error.DUPLICATE_EMAIL:This email has already been imported.`;
  }
  if (problem?.code === 'GMAIL_SYNC_IN_PROGRESS') {
    return $localize`:@@error.GMAIL_SYNC_IN_PROGRESS:A Gmail sync is already running. Wait for it to finish.`;
  }
  if (problem?.code === 'LOCAL_ONLY') {
    return $localize`:@@error.LOCAL_ONLY:Gmail settings can only be changed from this computer (open Investary via http://localhost).`;
  }
  if (problem?.code === 'DUPLICATE_SOURCE_RULE') {
    return $localize`:@@error.DUPLICATE_SOURCE_RULE:A source with this name already exists.`;
  }
  if (problem?.code === 'DECISION_CONFLICT') {
    return $localize`:@@error.DECISION_CONFLICT:This decision was changed elsewhere. Reload it and try again.`;
  }
  if (problem?.code === 'NOTEBOOK_CONFLICT') {
    return $localize`:@@error.NOTEBOOK_CONFLICT:This notebook was changed elsewhere. Reload it and try again.`;
  }
  if (problem?.code === 'NOTEBOOK_EMPTY') {
    return $localize`:@@error.NOTEBOOK_EMPTY:This notebook has no indexed sources yet. Add reports or emails that have been indexed.`;
  }
  if (problem?.code === 'NOTE_LIMIT_REACHED') {
    return $localize`:@@error.NOTE_LIMIT_REACHED:The daily limit for generated notes has been reached. Try again tomorrow.`;
  }
  if (problem?.errors) {
    return Object.entries(problem.errors)
      .map(([field, message]) => `${field}: ${message}`)
      .join('; ');
  }
  return problem?.detail ?? fallback;
}
