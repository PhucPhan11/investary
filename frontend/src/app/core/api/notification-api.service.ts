import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { AppNotification, NotificationType } from '../models/automation.model';
import { Page } from '../models/document.model';

export const NOTIFICATIONS_API = '/api/v1/notifications';

/** In-app notifications. Only the local backend creates them; nothing is pushed outside the app. */
@Injectable({ providedIn: 'root' })
export class NotificationApiService {
  private readonly http = inject(HttpClient);

  list(unreadOnly = false, type: NotificationType | '' = '', page = 0, size = 20): Observable<Page<AppNotification>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (unreadOnly) {
      params = params.set('unreadOnly', true);
    }
    if (type) {
      params = params.set('type', type);
    }
    return this.http.get<Page<AppNotification>>(NOTIFICATIONS_API, { params });
  }

  unreadCount(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${NOTIFICATIONS_API}/unread-count`);
  }

  setRead(id: string, read: boolean): Observable<AppNotification> {
    return this.http.patch<AppNotification>(`${NOTIFICATIONS_API}/${encodeURIComponent(id)}/${read ? 'read' : 'unread'}`, null);
  }

  readAll(): Observable<{ updated: number }> {
    return this.http.post<{ updated: number }>(`${NOTIFICATIONS_API}/read-all`, null);
  }
}

/** Router link of what a notification refers to, or null when it has no page. */
export function notificationLink(n: AppNotification): string[] | null {
  if (!n.entityId) {
    return null;
  }
  switch (n.entityType) {
    case 'EMAIL':
      return ['/emails', n.entityId];
    case 'DOCUMENT':
      return ['/documents', n.entityId];
    case 'BRIEF':
      return ['/briefs', n.entityId];
    case 'ASSESSMENT':
    case 'PORTFOLIO':
      return n.portfolioId ? ['/portfolios', n.portfolioId] : null;
    case 'JOB':
      return ['/automation/jobs', n.entityId];
    case 'BRIEF_JOB':
      return ['/automation/jobs'];
    case 'GMAIL':
      return ['/automation'];
    default:
      return null;
  }
}
