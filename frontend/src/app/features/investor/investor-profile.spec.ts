import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { InvestorProfile } from '../../core/models/portfolio.model';
import { InvestorProfilePage } from './investor-profile';

const empty: InvestorProfile = {
  configured: false,
  experienceLevel: null,
  riskTolerance: null,
  objectives: null,
  investmentHorizon: null,
  preferredInstruments: [],
  restrictions: null,
  liquidityNeeds: null,
  notes: null,
  updatedAt: null,
};

describe('InvestorProfilePage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [InvestorProfilePage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render() {
    const fixture = TestBed.createComponent(InvestorProfilePage);
    fixture.detectChanges();
    http.expectOne('/api/v1/investor/profile').flush(empty);
    http.expectOne('/api/v1/investor/goals').flush([]);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('saves the profile with the chosen preferences', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain('Not set yet');

    const risk = el.querySelector<HTMLSelectElement>('select[name="riskTolerance"]')!;
    risk.value = risk.options[2].value;
    risk.dispatchEvent(new Event('change'));
    const etf = Array.from(el.querySelectorAll<HTMLInputElement>('.checkbox-row input'))[1];
    etf.checked = true;
    etf.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    el.querySelector<HTMLFormElement>('form')!.dispatchEvent(new Event('submit'));

    const request = http.expectOne('/api/v1/investor/profile');
    expect(request.request.method).toBe('PUT');
    expect(request.request.body.riskTolerance).toBe('MODERATE');
    expect(request.request.body.preferredInstruments).toEqual(['ETF']);
    request.flush({ ...empty, configured: true, riskTolerance: 'MODERATE', preferredInstruments: ['ETF'] });
    fixture.detectChanges();
    expect(el.textContent).toContain('Profile saved.');
  });

  it('validates goals before sending them', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;

    const name = el.querySelector<HTMLInputElement>('input[name="goalName"]')!;
    name.value = 'Hưu trí';
    name.dispatchEvent(new Event('input'));
    const currency = el.querySelector<HTMLInputElement>('input[name="goalCurrency"]')!;
    currency.value = 'VN';
    currency.dispatchEvent(new Event('input'));
    fixture.detectChanges();

    expect(el.querySelector('.form-problems')!.textContent).toContain('3-letter currency');
    currency.value = 'vnd';
    currency.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    el.querySelectorAll<HTMLFormElement>('form')[1].dispatchEvent(new Event('submit'));

    const request = http.expectOne('/api/v1/investor/goals');
    expect(request.request.body).toEqual({
      name: 'Hưu trí',
      description: null,
      targetAmount: null,
      targetDate: null,
      currency: 'VND',
      priority: 'MEDIUM',
      notes: null,
    });
    request.flush({});
    http.expectOne('/api/v1/investor/goals').flush([]);
  });
});
