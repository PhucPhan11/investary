import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  AskResult,
  Note,
  NoteKind,
  NotebookDetail,
  NotebookMessage,
  NotebookSummary,
  SourceFilter,
} from '../models/notebook.model';

export const NOTEBOOKS_API = '/api/v1/notebooks';

@Injectable({ providedIn: 'root' })
export class NotebookApiService {
  private readonly http = inject(HttpClient);

  list(): Observable<NotebookSummary[]> {
    return this.http.get<NotebookSummary[]>(NOTEBOOKS_API);
  }

  create(title: string, description: string | null): Observable<NotebookDetail> {
    return this.http.post<NotebookDetail>(NOTEBOOKS_API, { title, description });
  }

  get(id: string): Observable<NotebookDetail> {
    return this.http.get<NotebookDetail>(url(id));
  }

  update(id: string, title: string, description: string | null, expectedVersion: number): Observable<NotebookDetail> {
    return this.http.patch<NotebookDetail>(url(id), { title, description, expectedVersion });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(url(id));
  }

  addSources(id: string, sources: { documentIds?: string[]; messageIds?: string[]; filter?: SourceFilter }): Observable<NotebookDetail> {
    const body: Record<string, unknown> = {};
    if (sources.documentIds?.length) {
      body['documentIds'] = sources.documentIds;
    }
    if (sources.messageIds?.length) {
      body['messageIds'] = sources.messageIds;
    }
    if (sources.filter) {
      body['filter'] = clean(sources.filter);
    }
    return this.http.post<NotebookDetail>(`${url(id)}/sources`, body);
  }

  removeSource(id: string, sourceId: string): Observable<NotebookDetail> {
    return this.http.delete<NotebookDetail>(`${url(id)}/sources/${encodeURIComponent(sourceId)}`);
  }

  messages(id: string): Observable<NotebookMessage[]> {
    return this.http.get<NotebookMessage[]>(`${url(id)}/messages`);
  }

  ask(id: string, question: string): Observable<AskResult> {
    return this.http.post<AskResult>(`${url(id)}/ask`, { question });
  }

  clearMessages(id: string): Observable<void> {
    return this.http.delete<void>(`${url(id)}/messages`);
  }

  notes(id: string): Observable<Note[]> {
    return this.http.get<Note[]>(`${url(id)}/notes`);
  }

  note(id: string, noteId: string): Observable<Note> {
    return this.http.get<Note>(`${url(id)}/notes/${encodeURIComponent(noteId)}`);
  }

  /** 202 with a PENDING note, or 200 with an identical earlier note (`reused`). */
  requestNote(id: string, kind: NoteKind, ticker: string | null): Observable<Note> {
    return this.http.post<Note>(`${url(id)}/notes`, ticker ? { kind, ticker } : { kind });
  }

  retryNote(id: string, noteId: string): Observable<Note> {
    return this.http.post<Note>(`${url(id)}/notes/${encodeURIComponent(noteId)}/retry`, null);
  }
}

function url(id: string): string {
  return `${NOTEBOOKS_API}/${encodeURIComponent(id)}`;
}

function clean(filter: SourceFilter): SourceFilter {
  return Object.fromEntries(Object.entries(filter).filter(([, value]) => value !== null && value !== undefined && `${value}`.trim() !== ''));
}
