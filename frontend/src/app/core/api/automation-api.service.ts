import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  AiUsage,
  AutomationJob,
  OverviewReport,
  GmailStatus,
  JobDetail,
  JobFilters,
  OverviewPeriod,
  ResearchWorkflow,
  SettingValue,
  SettingsSection,
  SourceRule,
  SourceRuleRequest,
  SyncRun,
} from '../models/automation.model';
import { Page } from '../models/document.model';

export const GMAIL_API = '/api/v1/integrations/gmail';
export const SOURCES_API = '/api/v1/automation/sources';
export const WORKFLOWS_API = '/api/v1/automation/workflows';
export const JOBS_API = '/api/v1/automation/jobs';
export const AUTOMATION_API = '/api/v1/automation';

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

/** Automated research processing: workflow state per email or document, manual processing and job retry. */
@Injectable({ providedIn: 'root' })
export class WorkflowApiService {
  private readonly http = inject(HttpClient);

  workflow(sourceId: string): Observable<ResearchWorkflow> {
    return this.http.get<ResearchWorkflow>(`${WORKFLOWS_API}/${encodeURIComponent(sourceId)}`);
  }

  process(sourceId: string): Observable<ResearchWorkflow> {
    return this.http.post<ResearchWorkflow>(`${WORKFLOWS_API}/${encodeURIComponent(sourceId)}/process`, null);
  }

  retry(jobId: string): Observable<AutomationJob> {
    return this.http.post<AutomationJob>(`${JOBS_API}/${encodeURIComponent(jobId)}/retry`, null);
  }
}

/** Automation Center: overview, job history, runtime settings and AI usage (Phase 5 / Milestone 4). */
@Injectable({ providedIn: 'root' })
export class AutomationCenterApiService {
  private readonly http = inject(HttpClient);

  overview(period: OverviewPeriod): Observable<OverviewReport> {
    return this.http.get<OverviewReport>(`${AUTOMATION_API}/overview`, { params: new HttpParams().set('period', period) });
  }

  jobs(filters: JobFilters, page = 0, size = 20): Observable<Page<AutomationJob>> {
    let params = new HttpParams().set('page', page).set('size', size);
    for (const [key, value] of Object.entries(filters)) {
      if (value) {
        params = params.set(key, value);
      }
    }
    return this.http.get<Page<AutomationJob>>(JOBS_API, { params });
  }

  job(id: string): Observable<JobDetail> {
    return this.http.get<JobDetail>(`${JOBS_API}/${encodeURIComponent(id)}`);
  }

  retry(id: string): Observable<AutomationJob> {
    return this.http.post<AutomationJob>(`${JOBS_API}/${encodeURIComponent(id)}/retry`, null);
  }

  settings(): Observable<SettingsSection[]> {
    return this.http.get<SettingsSection[]>(`${AUTOMATION_API}/settings`);
  }

  /** Saves some fields of a section; null resets a field to its default. */
  saveSettings(section: string, version: number, values: Record<string, SettingValue>): Observable<SettingsSection> {
    return this.http.put<SettingsSection>(`${AUTOMATION_API}/settings/${encodeURIComponent(section)}`, { version, values });
  }

  resetSetting(section: string, field: string): Observable<SettingsSection> {
    return this.http.delete<SettingsSection>(
      `${AUTOMATION_API}/settings/${encodeURIComponent(section)}/${encodeURIComponent(field)}`,
    );
  }

  usage(period: 'day' | 'week', days: number): Observable<AiUsage> {
    return this.http.get<AiUsage>(`${AUTOMATION_API}/usage`, { params: new HttpParams().set('period', period).set('days', days) });
  }
}
