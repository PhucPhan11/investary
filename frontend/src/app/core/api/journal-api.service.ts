import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Page } from '../models/document.model';
import {
  DecisionDetail,
  DecisionFilters,
  DecisionRequest,
  DecisionRevision,
  DecisionSummary,
  EvidenceRequest,
} from '../models/journal.model';

export const DECISION_API = '/api/v1/decisions';

/** The investment decision journal. Writes carry the version the user saw; a stale one is a 409 conflict. */
@Injectable({ providedIn: 'root' })
export class JournalApiService {
  private readonly http = inject(HttpClient);

  list(filters: DecisionFilters = {}, page = 0, size = 20): Observable<Page<DecisionSummary>> {
    let params = new HttpParams().set('page', page).set('size', size);
    for (const [key, value] of Object.entries(filters)) {
      if (typeof value === 'string' && value.trim()) {
        params = params.set(key, value.trim());
      }
    }
    return this.http.get<Page<DecisionSummary>>(DECISION_API, { params });
  }

  get(id: string): Observable<DecisionDetail> {
    return this.http.get<DecisionDetail>(this.url(id));
  }

  create(request: DecisionRequest): Observable<DecisionDetail> {
    return this.http.post<DecisionDetail>(DECISION_API, request);
  }

  update(id: string, request: DecisionRequest): Observable<DecisionDetail> {
    return this.http.put<DecisionDetail>(this.url(id), request);
  }

  finalize(id: string, expectedVersion: number, reason?: string | null): Observable<DecisionDetail> {
    return this.http.post<DecisionDetail>(`${this.url(id)}/finalize`, { expectedVersion, reason: reason ?? null });
  }

  execute(id: string, expectedVersion: number, reason?: string | null): Observable<DecisionDetail> {
    return this.http.post<DecisionDetail>(`${this.url(id)}/execute`, { expectedVersion, reason: reason ?? null });
  }

  cancel(id: string, expectedVersion: number, reason: string): Observable<DecisionDetail> {
    return this.http.post<DecisionDetail>(`${this.url(id)}/cancel`, { expectedVersion, reason });
  }

  history(id: string): Observable<DecisionRevision[]> {
    return this.http.get<DecisionRevision[]>(`${this.url(id)}/history`);
  }

  attachEvidence(id: string, request: EvidenceRequest): Observable<DecisionDetail> {
    return this.http.post<DecisionDetail>(`${this.url(id)}/evidence`, request);
  }

  removeEvidence(id: string, evidenceId: string, expectedVersion: number, reason: string): Observable<DecisionDetail> {
    const params = new HttpParams().set('expectedVersion', expectedVersion).set('reason', reason);
    return this.http.delete<DecisionDetail>(`${this.url(id)}/evidence/${encodeURIComponent(evidenceId)}`, { params });
  }

  linkTransaction(id: string, transactionId: string, expectedVersion: number, note?: string | null): Observable<DecisionDetail> {
    return this.http.post<DecisionDetail>(`${this.url(id)}/transactions`, { transactionId, expectedVersion, note: note ?? null });
  }

  unlinkTransaction(id: string, linkId: string, expectedVersion: number, reason: string): Observable<DecisionDetail> {
    const params = new HttpParams().set('expectedVersion', expectedVersion).set('reason', reason);
    return this.http.delete<DecisionDetail>(`${this.url(id)}/transactions/${encodeURIComponent(linkId)}`, { params });
  }

  private url(id: string): string {
    return `${DECISION_API}/${encodeURIComponent(id)}`;
  }
}
