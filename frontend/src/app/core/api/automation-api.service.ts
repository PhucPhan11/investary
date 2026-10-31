import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { GmailStatus, SourceRule, SourceRuleRequest, SyncRun } from '../models/automation.model';
import { Page } from '../models/document.model';

export const GMAIL_API = '/api/v1/integrations/gmail';
export const SOURCES_API = '/api/v1/automation/sources';

/** Gmail connection and sync. The backend keeps all tokens; the browser only gets Google's consent URL. */
@Injectable({ providedIn: 'root' })
export class GmailApiService {
  private readonly http = inject(HttpClient);

  status(): Observable<GmailStatus> {
    return this.http.get<GmailStatus>(`${GMAIL_API}/status`);
  }

  connect(): Observable<{ authorizationUrl: string }> {
    return this.http.post<{ authorizationUrl: string }>(`${GMAIL_API}/connect`, null);
  }

  disconnect(): Observable<void> {
    return this.http.post<void>(`${GMAIL_API}/disconnect`, null);
  }

  sync(): Observable<{ runId: string }> {
    return this.http.post<{ runId: string }>(`${GMAIL_API}/sync`, null);
  }

  history(page = 0, size = 10): Observable<Page<SyncRun>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<SyncRun>>(`${GMAIL_API}/sync-history`, { params });
  }
}

/** Brokerage source rules: which verified senders Gmail sync may import. */
@Injectable({ providedIn: 'root' })
export class SourceRuleApiService {
  private readonly http = inject(HttpClient);

  list(): Observable<SourceRule[]> {
    return this.http.get<SourceRule[]>(SOURCES_API);
  }

  create(request: SourceRuleRequest): Observable<SourceRule> {
    return this.http.post<SourceRule>(SOURCES_API, request);
  }

  update(id: string, request: SourceRuleRequest): Observable<SourceRule> {
    return this.http.put<SourceRule>(this.url(id), request);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(this.url(id));
  }

  private url(id: string): string {
    return `${SOURCES_API}/${encodeURIComponent(id)}`;
  }
}
