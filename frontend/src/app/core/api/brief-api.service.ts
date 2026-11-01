import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Brief, BriefFilters, BriefJob, BriefListItem } from '../models/brief.model';
import { Page } from '../models/document.model';

export const BRIEF_API = '/api/v1/briefs';

/** Daily briefs. Generation only enqueues a job; poll {@link job} for the result. */
@Injectable({ providedIn: 'root' })
export class BriefApiService {
  private readonly http = inject(HttpClient);

  list(filters: BriefFilters = {}, page = 0, size = 20): Observable<Page<BriefListItem>> {
    let params = new HttpParams().set('page', page).set('size', size);
    for (const [key, value] of Object.entries(filters)) {
      if (value) {
        params = params.set(key, value);
      }
    }
    return this.http.get<Page<BriefListItem>>(BRIEF_API, { params });
  }

  get(id: string): Observable<Brief> {
    return this.http.get<Brief>(`${BRIEF_API}/${encodeURIComponent(id)}`);
  }

  /** Omit the portfolio for a general brief and the date for today (in the brief time zone). */
  generate(portfolioId?: string, date?: string): Observable<BriefJob> {
    return this.http.post<BriefJob>(`${BRIEF_API}/generate`, { portfolioId: portfolioId || null, date: date || null });
  }

  regenerate(id: string): Observable<BriefJob> {
    return this.http.post<BriefJob>(`${BRIEF_API}/${encodeURIComponent(id)}/regenerate`, null);
  }

  job(id: string): Observable<BriefJob> {
    return this.http.get<BriefJob>(`${BRIEF_API}/jobs/${encodeURIComponent(id)}`);
  }
}
