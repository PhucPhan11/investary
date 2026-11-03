import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  Allocation,
  Goal,
  GoalRequest,
  Holding,
  Instrument,
  InstrumentRequest,
  InvestorProfile,
  Page,
  Portfolio,
  PortfolioRequest,
  PortfolioSummary,
  PriceObservation,
  PriceRequest,
  ProfileRequest,
  Transaction,
  TransactionRequest,
} from '../models/portfolio.model';

export const PORTFOLIO_API = '/api/v1/portfolios';
export const INSTRUMENT_API = '/api/v1/instruments';
export const INVESTOR_API = '/api/v1/investor';

/** Portfolios and transactions. Holdings, summary and allocation are computed by the backend. */
@Injectable({ providedIn: 'root' })
export class PortfolioApiService {
  private readonly http = inject(HttpClient);

  list(): Observable<Portfolio[]> {
    return this.http.get<Portfolio[]>(PORTFOLIO_API);
  }

  get(id: string): Observable<Portfolio> {
    return this.http.get<Portfolio>(`${PORTFOLIO_API}/${encodeURIComponent(id)}`);
  }

  create(request: PortfolioRequest): Observable<Portfolio> {
    return this.http.post<Portfolio>(PORTFOLIO_API, request);
  }

  update(id: string, request: PortfolioRequest): Observable<Portfolio> {
    return this.http.put<Portfolio>(`${PORTFOLIO_API}/${encodeURIComponent(id)}`, request);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${PORTFOLIO_API}/${encodeURIComponent(id)}`);
  }

  summary(id: string): Observable<PortfolioSummary> {
    return this.http.get<PortfolioSummary>(`${PORTFOLIO_API}/${encodeURIComponent(id)}/summary`);
  }

  holdings(id: string): Observable<Holding[]> {
    return this.http.get<Holding[]>(`${PORTFOLIO_API}/${encodeURIComponent(id)}/holdings`);
  }

  allocation(id: string): Observable<Allocation> {
    return this.http.get<Allocation>(`${PORTFOLIO_API}/${encodeURIComponent(id)}/allocation`);
  }

  transactions(id: string, page = 0, size = 50): Observable<Page<Transaction>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<Transaction>>(`${PORTFOLIO_API}/${encodeURIComponent(id)}/transactions`, { params });
  }

  addTransaction(id: string, request: TransactionRequest): Observable<Transaction> {
    return this.http.post<Transaction>(`${PORTFOLIO_API}/${encodeURIComponent(id)}/transactions`, request);
  }

  updateTransaction(id: string, transactionId: string, request: TransactionRequest): Observable<Transaction> {
    return this.http.put<Transaction>(
      `${PORTFOLIO_API}/${encodeURIComponent(id)}/transactions/${encodeURIComponent(transactionId)}`,
      request,
    );
  }

  deleteTransaction(id: string, transactionId: string): Observable<void> {
    return this.http.delete<void>(`${PORTFOLIO_API}/${encodeURIComponent(id)}/transactions/${encodeURIComponent(transactionId)}`);
  }
}

/** Instruments (entered by the user) and their manual price observations. */
@Injectable({ providedIn: 'root' })
export class InstrumentApiService {
  private readonly http = inject(HttpClient);

  list(query = ''): Observable<Instrument[]> {
    const params = query.trim() ? new HttpParams().set('q', query.trim()) : undefined;
    return this.http.get<Instrument[]>(INSTRUMENT_API, { params });
  }

  create(request: InstrumentRequest): Observable<Instrument> {
    return this.http.post<Instrument>(INSTRUMENT_API, request);
  }

  prices(id: string): Observable<PriceObservation[]> {
    return this.http.get<PriceObservation[]>(`${INSTRUMENT_API}/${encodeURIComponent(id)}/prices`);
  }

  addPrice(id: string, request: PriceRequest): Observable<PriceObservation> {
    return this.http.post<PriceObservation>(`${INSTRUMENT_API}/${encodeURIComponent(id)}/prices`, request);
  }

  deletePrice(id: string, priceId: string): Observable<void> {
    return this.http.delete<void>(`${INSTRUMENT_API}/${encodeURIComponent(id)}/prices/${encodeURIComponent(priceId)}`);
  }
}

/** The investor's self-declared profile and goals. */
@Injectable({ providedIn: 'root' })
export class InvestorApiService {
  private readonly http = inject(HttpClient);

  profile(): Observable<InvestorProfile> {
    return this.http.get<InvestorProfile>(`${INVESTOR_API}/profile`);
  }

  saveProfile(request: ProfileRequest): Observable<InvestorProfile> {
    return this.http.put<InvestorProfile>(`${INVESTOR_API}/profile`, request);
  }

  goals(): Observable<Goal[]> {
    return this.http.get<Goal[]>(`${INVESTOR_API}/goals`);
  }

  createGoal(request: GoalRequest): Observable<Goal> {
    return this.http.post<Goal>(`${INVESTOR_API}/goals`, request);
  }

  updateGoal(id: string, request: GoalRequest): Observable<Goal> {
    return this.http.put<Goal>(`${INVESTOR_API}/goals/${encodeURIComponent(id)}`, request);
  }

  deleteGoal(id: string): Observable<void> {
    return this.http.delete<void>(`${INVESTOR_API}/goals/${encodeURIComponent(id)}`);
  }
}
