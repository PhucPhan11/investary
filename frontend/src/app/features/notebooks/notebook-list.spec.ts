import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';

import { NOTEBOOK } from '../../testing/notebook-fixtures';
import { NotebookList } from './notebook-list';

describe('NotebookList', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists notebooks with their counts', () => {
    const fixture = TestBed.createComponent(NotebookList);
    fixture.detectChanges();
    http.expectOne('/api/v1/notebooks').flush([
      { ...NOTEBOOK, sourceCount: 2, questionCount: 5, noteCount: 1 },
    ]);
    fixture.detectChanges();
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('SCS 2026');
    expect(fixture.nativeElement.querySelector('a[href="/notebooks/nb1"]')).not.toBeNull();
  });

  it('shows the empty state and creates a notebook', async () => {
    const fixture = TestBed.createComponent(NotebookList);
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    fixture.detectChanges();
    http.expectOne('/api/v1/notebooks').flush([]);
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('No notebooks yet');

    const title: HTMLInputElement = fixture.nativeElement.querySelector('input[name="title"]');
    title.value = '  SCS 2026 ';
    title.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    fixture.nativeElement.querySelector('form.notebook-create').dispatchEvent(new Event('submit'));

    const request = http.expectOne('/api/v1/notebooks');
    expect(request.request.body).toEqual({ title: 'SCS 2026', description: null });
    request.flush(NOTEBOOK);
    expect(navigate).toHaveBeenCalledWith(['/notebooks', 'nb1']);
  });
});
