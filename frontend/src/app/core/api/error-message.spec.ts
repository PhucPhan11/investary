import { HttpErrorResponse } from '@angular/common/http';

import { errorMessage } from './error-message';

describe('errorMessage', () => {
  const http = (status: number, error: unknown = null) => new HttpErrorResponse({ status, error });

  it('explains an unreachable backend', () => {
    expect(errorMessage(http(0))).toContain('Cannot reach');
  });

  it('maps duplicates to a friendly message', () => {
    expect(errorMessage(http(409, { code: 'DUPLICATE_DOCUMENT', detail: 'x' }))).toBe(
      'This document has already been uploaded.',
    );
  });

  it('lists field errors', () => {
    expect(errorMessage(http(400, { errors: { ticker: 'bad' } }))).toBe('ticker: bad');
  });

  it('falls back to problem detail, then default', () => {
    expect(errorMessage(http(400, { detail: 'Only PDF documents are accepted' }))).toBe(
      'Only PDF documents are accepted',
    );
    expect(errorMessage(new Error('boom'), 'fallback')).toBe('fallback');
  });
});
