import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { Allocation, Holding, Portfolio, PortfolioSummary } from '../../core/models/portfolio.model';
import { PortfolioDashboard } from './portfolio-dashboard';

const portfolio: Portfolio = {
  id: 'p1',
  name: 'Danh mục thử',
  description: null,
  baseCurrency: 'VND',
  transactionCount: 2,
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-01T00:00:00Z',
};

function summary(overrides: Partial<PortfolioSummary>): PortfolioSummary {
  return {
    portfolioId: 'p1',
    name: 'Danh mục thử',
    currency: 'VND',
    openHoldings: 2,
    closedPositions: 0,
    transactionCount: 2,
    totalCostBasis: 30000000,
    realizedPnl: 0,
    valuationStatus: 'UNAVAILABLE',
    pricedHoldings: 0,
    stalePricedHoldings: 0,
    pricedCostBasis: 0,
    pricedMarketValue: null,
    pricedUnrealizedPnl: null,
    pricedCoveragePct: null,
    totalMarketValue: null,
    unrealizedPnl: null,
    unrealizedPnlPct: null,
    valuationDate: '2026-10-30',
    oldestPriceAsOf: null,
    priceStaleAfterDays: 7,
    limitations: ['Giá vốn bình quân gia quyền.'],
    ...overrides,
  };
}

function holding(symbol: string, priced: boolean): Holding {
  return {
    instrumentId: symbol,
    symbol,
    name: symbol,
    instrumentType: 'STOCK',
    exchange: 'HOSE',
    quantity: 100,
    averageCost: 100000,
    costBasis: 10000000,
    realizedPnl: 0,
    firstTradeDate: '2026-09-01',
    lastTradeDate: '2026-09-01',
    costWeightPct: 50,
    valuationStatus: priced ? 'AVAILABLE' : 'UNAVAILABLE',
    latestPrice: priced ? 110000 : null,
    priceAsOf: priced ? '2026-10-29' : null,
    priceSource: priced ? 'Bảng giá' : null,
    priceAgeDays: priced ? 1 : null,
    marketValue: priced ? 11000000 : null,
    unrealizedPnl: priced ? 1000000 : null,
    unrealizedPnlPct: priced ? 10 : null,
    marketWeightPct: null,
  };
}

const allocation: Allocation = {
  portfolioId: 'p1',
  currency: 'VND',
  valuationStatus: 'PARTIAL',
  costBasis: {
    basis: 'COST_BASIS',
    total: 20000000,
    byInstrument: [
      { key: 'FPT', label: 'FPT', value: 10000000, weightPct: 50 },
      { key: 'SCS', label: 'SCS', value: 10000000, weightPct: 50 },
    ],
    byInstrumentType: [{ key: 'STOCK', label: 'STOCK', value: 20000000, weightPct: 100 }],
  },
  marketValue: null,
  marketValueUnavailableReason: 'Chưa có giá cho 1/2 mã.',
  limitations: [],
};

describe('PortfolioDashboard', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PortfolioDashboard],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function render(s: PortfolioSummary, holdings: Holding[]) {
    const fixture = TestBed.createComponent(PortfolioDashboard);
    fixture.componentRef.setInput('id', 'p1');
    fixture.detectChanges();
    http.expectOne('/api/v1/portfolios/p1').flush(portfolio);
    http.expectOne('/api/v1/portfolios/p1/summary').flush(s);
    http.expectOne('/api/v1/portfolios/p1/holdings').flush(holdings);
    http.expectOne('/api/v1/portfolios/p1/allocation').flush(allocation);
    http.expectOne((r) => r.url === '/api/v1/portfolios/p1/transactions').flush({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
    http.expectOne('/api/v1/instruments').flush([]);
    fixture.detectChanges();
    http.expectOne('/api/v1/intelligence/portfolios/p1/impact-assessments').flush([]);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('never shows a market value or unrealized P&L without prices', () => {
    const el = render(summary({}), [holding('FPT', false), holding('SCS', false)]);

    expect(el.querySelector('[data-testid="market-value-unavailable"]')).not.toBeNull();
    expect(el.querySelector('[data-testid="pnl-unavailable"]')).not.toBeNull();
    expect(el.textContent).toContain('No prices');
    expect(el.textContent).toContain('No price');
    expect(el.querySelector('[data-testid="market-allocation-unavailable"]')!.textContent).toContain('1/2');
    expect(el.textContent).toContain('Allocation by cost basis');
  });

  it('labels a partial valuation and shows the priced subset separately', () => {
    const el = render(
      summary({ valuationStatus: 'PARTIAL', pricedHoldings: 1, pricedMarketValue: 11000000, pricedCoveragePct: 50 }),
      [holding('FPT', true), holding('SCS', false)],
    );

    expect(el.textContent).toContain('Partially valued');
    expect(el.textContent).toContain('Priced holdings only');
    expect(el.querySelector('[data-testid="pnl-unavailable"]')).not.toBeNull();
    expect(el.textContent).toContain('11,000,000');
  });

  it('shows totals when every holding is priced', () => {
    const el = render(
      summary({ valuationStatus: 'AVAILABLE', pricedHoldings: 2, totalMarketValue: 22000000, unrealizedPnl: 2000000, unrealizedPnlPct: 10 }),
      [holding('FPT', true), holding('SCS', true)],
    );

    expect(el.textContent).toContain('22,000,000');
    expect(el.querySelector('.pnl--gain')).not.toBeNull();
    expect(el.querySelector('[data-testid="market-value-unavailable"]')).toBeNull();
  });
});
