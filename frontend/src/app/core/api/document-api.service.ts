import { HttpClient, HttpEvent, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { Page, ResearchDocument, UploadMetadata } from '../models/document.model';

export const DOCUMENTS_API = '/api/v1/documents';

@Injectable({ providedIn: 'root' })
export class DocumentApiService {
  private readonly http = inject(HttpClient);

  list(page = 0, size = 20): Observable<Page<ResearchDocument>> {
    const params = new HttpParams()
      .set('page', page)
      .set('size', size)
      .set('sort', 'uploadedAt,desc');
    return this.http.get<Page<ResearchDocument>>(DOCUMENTS_API, { params });
  }

  get(id: string): Observable<ResearchDocument> {
    return this.http.get<ResearchDocument>(`${DOCUMENTS_API}/${encodeURIComponent(id)}`);
  }

  upload(file: File, metadata: UploadMetadata): Observable<HttpEvent<ResearchDocument>> {
    const form = new FormData();
    form.append('file', file, file.name);
    for (const [key, value] of Object.entries(metadata)) {
      if (value !== undefined && value !== null && `${value}`.trim() !== '') {
        form.append(key, `${value}`.trim());
      }
    }
    return this.http.post<ResearchDocument>(DOCUMENTS_API, form, {
      observe: 'events',
      reportProgress: true,
    });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${DOCUMENTS_API}/${encodeURIComponent(id)}`);
  }

  downloadUrl(id: string): string {
    return `${DOCUMENTS_API}/${encodeURIComponent(id)}/download`;
  }
}
