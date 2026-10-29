import { HttpClient, HttpEvent, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Page } from '../models/document.model';
import {
  EmailDetail,
  EmailQuery,
  EmailSummary,
  LinkedDocument,
  ResearchItem,
  Sender,
  SourceLink,
} from '../models/email.model';

export const EMAILS_API = '/api/v1/emails';

@Injectable({ providedIn: 'root' })
export class EmailApiService {
  private readonly http = inject(HttpClient);

  list(query: EmailQuery = {}): Observable<Page<EmailSummary>> {
    let params = new HttpParams()
      .set('page', query.page ?? 0)
      .set('size', query.size ?? 20)
      .set('sort', 'importedAt,desc');
    if (query.q?.trim()) {
      params = params.set('q', query.q.trim());
    }
    if (query.sender) {
      params = params.set('sender', query.sender);
    }
    if (query.documentId) {
      params = params.set('documentId', query.documentId);
    }
    return this.http.get<Page<EmailSummary>>(EMAILS_API, { params });
  }

  senders(): Observable<Sender[]> {
    return this.http.get<Sender[]>(`${EMAILS_API}/senders`);
  }

  get(id: string): Observable<EmailDetail> {
    return this.http.get<EmailDetail>(this.url(id));
  }

  import(file: File): Observable<HttpEvent<EmailDetail>> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<EmailDetail>(`${EMAILS_API}/import`, form, {
      observe: 'events',
      reportProgress: true,
    });
  }

  links(id: string): Observable<SourceLink[]> {
    return this.http.get<SourceLink[]>(`${this.url(id)}/links`);
  }

  requestDownload(id: string, linkId: string): Observable<SourceLink> {
    return this.http.post<SourceLink>(`${this.url(id)}/links/${encodeURIComponent(linkId)}/download`, null);
  }

  documents(id: string): Observable<LinkedDocument[]> {
    return this.http.get<LinkedDocument[]>(`${this.url(id)}/documents`);
  }

  researchItems(id: string): Observable<ResearchItem[]> {
    return this.http.get<ResearchItem[]>(`${this.url(id)}/research-items`);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(this.url(id));
  }

  /** Sanitized, inert HTML preview served with a strict Content-Security-Policy. */
  htmlUrl(id: string): string {
    return `${this.url(id)}/html`;
  }

  originalUrl(id: string): string {
    return `${this.url(id)}/original`;
  }

  private url(id: string): string {
    return `${EMAILS_API}/${encodeURIComponent(id)}`;
  }
}
