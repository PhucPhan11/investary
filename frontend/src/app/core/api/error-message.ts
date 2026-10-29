import { HttpErrorResponse } from '@angular/common/http';

import { ProblemDetail } from '../models/document.model';

/** Turns an HTTP error into a short, user-facing message. */
export function errorMessage(error: unknown, fallback = 'Something went wrong. Please try again.'): string {
  if (!(error instanceof HttpErrorResponse)) {
    return fallback;
  }
  if (error.status === 0) {
    return 'Cannot reach the Investary server. Is the backend running?';
  }
  const problem = (typeof error.error === 'object' ? error.error : null) as ProblemDetail | null;
  if (error.status === 413) {
    return 'The file is larger than the allowed upload size.';
  }
  if (problem?.code === 'DUPLICATE_DOCUMENT') {
    return 'This document has already been uploaded.';
  }
  if (problem?.code === 'DUPLICATE_EMAIL') {
    return 'This email has already been imported.';
  }
  if (problem?.errors) {
    return Object.entries(problem.errors)
      .map(([field, message]) => `${field}: ${message}`)
      .join('; ');
  }
  return problem?.detail ?? fallback;
}
