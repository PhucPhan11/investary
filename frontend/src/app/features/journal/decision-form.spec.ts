import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';

import { decision } from '../../testing/journal-fixtures';
import { DecisionForm } from './decision-form';

const INSTRUMENTS = [{ id: 'i1', symbol: 'FPT', name: 'FPT', instrumentType: 'STOCK', exchange: 'HOSE', currency: 'VND', aliases: [] }];

describe('DecisionForm', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function create(id?: string) {
    const fixture = TestBed.createComponent(DecisionForm);
    if (id) {
      fixture.componentRef.setInput('id', id);
    }
    fixture.detectChanges();
    http.expectOne('/api/v1/portfolios').flush([{ id: 'p1', name: 'Danh mục chính' }]);
    http.expectOne('/api/v1/instruments').flush(INSTRUMENTS);
    return fixture;
  }

  function type(fixture: { nativeElement: HTMLElement }, selector: string, value: string, event = 'input') {
    const element = fixture.nativeElement.querySelector(selector) as HTMLInputElement | HTMLSelectElement;
    element.value = value;
    element.dispatchEvent(new Event(event));
  }

  it('creates a draft from the typed values', async () => {
    const fixture = await create();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture.detectChanges();
    await fixture.whenStable();

    type(fixture, 'select[name="portfolioId"]', 'p1', 'change');
    type(fixture, 'select[name="instrumentId"]', 'i1', 'change');
    type(fixture, 'input[name="title"]', 'Mua thêm FPT');
    type(fixture, 'textarea[name="rationale"]', 'Luận điểm tổng hợp');
    type(fixture, 'input[name="intendedQuantity"]', '20');
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));

    const request = http.expectOne('/api/v1/decisions');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toMatchObject({
      portfolioId: 'p1',
      instrumentId: 'i1',
      decisionType: 'BUY',
      title: 'Mua thêm FPT',
      rationale: 'Luận điểm tổng hợp',
      intendedQuantity: 20,
      expectedVersion: null,
    });
    request.flush(decision());
    expect(navigate).toHaveBeenCalledWith(['/journal', 'd1']);
  });

  it('amends a finalized decision with locked fields and a required reason', async () => {
    const fixture = await create('d1');
    http.expectOne('/api/v1/decisions/d1').flush(decision({ status: 'DECIDED', version: 4, actions: ['AMEND'] }));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(fixture.nativeElement.textContent).toContain('Amend decision');
    expect((fixture.nativeElement.querySelector('select[name="decisionType"]') as HTMLSelectElement).disabled).toBe(true);
    expect((fixture.nativeElement.querySelector('input[name="intendedQuantity"]') as HTMLInputElement).disabled).toBe(true);

    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Explain why you are amending');

    vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    type(fixture, 'textarea[name="risks"]', 'Rủi ro mới');
    type(fixture, 'input[name="amendmentReason"]', 'Thêm rủi ro');
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    const request = http.expectOne('/api/v1/decisions/d1');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body).toMatchObject({ risks: 'Rủi ro mới', amendmentReason: 'Thêm rủi ro', expectedVersion: 4 });
    request.flush(decision({ status: 'DECIDED', version: 5 }));
  });

  it('shows a conflict when the decision changed elsewhere', async () => {
    const fixture = await create('d1');
    http.expectOne('/api/v1/decisions/d1').flush(decision());
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    http.expectOne('/api/v1/decisions/d1').flush({ code: 'DECISION_CONFLICT' }, { status: 409, statusText: 'Conflict' });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('changed elsewhere');
  });
});
