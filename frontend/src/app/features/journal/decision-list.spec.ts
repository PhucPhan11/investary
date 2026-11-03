import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { SUMMARY } from '../../testing/journal-fixtures';
import { DecisionList } from './decision-list';

describe('DecisionList', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function create() {
    const fixture = TestBed.createComponent(DecisionList);
    fixture.detectChanges();
    http.expectOne('/api/v1/portfolios').flush([{ id: 'p1', name: 'Danh mục chính' }]);
    return fixture;
  }

  it('lists decisions with type, ticker, portfolio and status', () => {
    const fixture = create();
    http.expectOne('/api/v1/decisions?page=0&size=20').flush({ content: [SUMMARY], page: 0, size: 20, totalElements: 1, totalPages: 1 });
    fixture.detectChanges();
    const text: string = fixture.nativeElement.textContent;

    expect(text).toContain('Mua thêm FPT');
    expect(text).toContain('Buy');
    expect(text).toContain('Danh mục chính');
    expect(text).toContain('Decided');
    expect(fixture.nativeElement.querySelector('a[href="/journal/d1"]')).not.toBeNull();
  });

  it('filters by ticker and status', async () => {
    const fixture = create();
    http.expectOne('/api/v1/decisions?page=0&size=20').flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('No decisions recorded');

    const ticker: HTMLInputElement = fixture.nativeElement.querySelector('input[name="ticker"]');
    ticker.value = 'fpt';
    ticker.dispatchEvent(new Event('input'));
    const status: HTMLSelectElement = fixture.nativeElement.querySelector('select[name="status"]');
    status.value = status.options[2].value;
    status.dispatchEvent(new Event('change'));
    http.expectOne('/api/v1/decisions?page=0&size=20&ticker=fpt&status=DECIDED').flush({
      content: [SUMMARY], page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Mua thêm FPT');
  });

  it('shows an error when the journal cannot be loaded', () => {
    const fixture = create();
    http.expectOne('/api/v1/decisions?page=0&size=20').flush({ detail: 'down' }, { status: 500, statusText: 'Error' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.alert--error')).not.toBeNull();
  });
});
