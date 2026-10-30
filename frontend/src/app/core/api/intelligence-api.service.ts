import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  Assessment,
  AssessmentListItem,
  IntelligenceFilters,
  PortfolioAnalysis,
  PortfolioAnswer,
  RelevantResearch,
} from '../models/intelligence.model';

export const INTELLIGENCE_API = '/api/v1/intelligence';

/** Portfolio-aware research. These endpoints only read portfolio data. */
@Injectable({ providedIn: 'root' })
export class IntelligenceApiService {
  private readonly http = inject(HttpClient);

  analyze(portfolioId: string): Observable<PortfolioAnalysis> {
    return this.http.post<PortfolioAnalysis>(`${INTELLIGENCE_API}/portfolios/${encodeURIComponent(portfolioId)}/analyze`, null);
  }

  relevantResearch(portfolioId: string, filters: IntelligenceFilters = {}): Observable<RelevantResearch> {
    return this.http.post<RelevantResearch>(
      `${INTELLIGENCE_API}/portfolios/${encodeURIComponent(portfolioId)}/relevant-research`,
      clean(filters),
    );
  }

  createAssessment(portfolioId: string, filters: IntelligenceFilters = {}): Observable<Assessment> {
    return this.http.post<Assessment>(
      `${INTELLIGENCE_API}/portfolios/${encodeURIComponent(portfolioId)}/impact-assessments`,
      clean(filters),
    );
  }

  assessments(portfolioId: string): Observable<AssessmentListItem[]> {
    return this.http.get<AssessmentListItem[]>(`${INTELLIGENCE_API}/portfolios/${encodeURIComponent(portfolioId)}/impact-assessments`);
  }

  assessment(id: string): Observable<Assessment> {
    return this.http.get<Assessment>(`${INTELLIGENCE_API}/impact-assessments/${encodeURIComponent(id)}`);
  }

  /** The portfolio is always explicit; the backend never chooses one. */
  ask(question: string, portfolioId: string): Observable<PortfolioAnswer> {
    return this.http.post<PortfolioAnswer>(`${INTELLIGENCE_API}/ask`, { question, portfolioId });
  }
}

function clean(filters: IntelligenceFilters): IntelligenceFilters {
  const result: IntelligenceFilters = {};
  for (const [key, value] of Object.entries(filters)) {
    const trimmed = typeof value === 'string' ? value.trim() : value;
    if (trimmed) {
      (result as Record<string, unknown>)[key] = trimmed;
    }
  }
  return result;
}
