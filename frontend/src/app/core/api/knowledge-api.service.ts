import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  AssistantAnswer,
  KnowledgeJob,
  KnowledgeSourceType,
  KnowledgeStatus,
  ReindexScope,
  ResearchFilters,
} from '../models/knowledge.model';

export const KNOWLEDGE_API = '/api/v1/knowledge';
export const RESEARCH_API = '/api/v1/research';

@Injectable({ providedIn: 'root' })
export class KnowledgeApiService {
  private readonly http = inject(HttpClient);

  status(): Observable<KnowledgeStatus> {
    return this.http.get<KnowledgeStatus>(`${KNOWLEDGE_API}/status`);
  }

  sourceStatus(type: KnowledgeSourceType, id: string): Observable<KnowledgeJob> {
    return this.http.get<KnowledgeJob>(`${KNOWLEDGE_API}/${segment(type)}/${encodeURIComponent(id)}`);
  }

  /** Queues indexing; also used to retry a failed source or reindex an indexed one. */
  index(type: KnowledgeSourceType, id: string): Observable<KnowledgeJob> {
    return this.http.post<KnowledgeJob>(`${KNOWLEDGE_API}/index/${segment(type)}/${encodeURIComponent(id)}`, null);
  }

  reindex(scope: ReindexScope): Observable<{ scope: ReindexScope; queued: number }> {
    return this.http.post<{ scope: ReindexScope; queued: number }>(`${KNOWLEDGE_API}/reindex`, { scope });
  }
}

@Injectable({ providedIn: 'root' })
export class ResearchApiService {
  private readonly http = inject(HttpClient);

  ask(question: string, filters: ResearchFilters = {}): Observable<AssistantAnswer> {
    return this.http.post<AssistantAnswer>(`${RESEARCH_API}/ask`, { question, ...clean(filters) });
  }
}

function segment(type: KnowledgeSourceType): string {
  return type === 'DOCUMENT' ? 'documents' : 'emails';
}

/** Drops empty filter values so the backend sees "no filter" rather than an empty string. */
function clean(filters: ResearchFilters): ResearchFilters {
  const result: ResearchFilters = {};
  for (const [key, value] of Object.entries(filters)) {
    const trimmed = typeof value === 'string' ? value.trim() : value;
    if (trimmed) {
      (result as Record<string, unknown>)[key] = trimmed;
    }
  }
  return result;
}
