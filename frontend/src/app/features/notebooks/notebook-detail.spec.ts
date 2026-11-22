import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ANSWER, CONSENSUS_NOTE, NOTEBOOK, QUESTION } from '../../testing/notebook-fixtures';
import { NotebookDetailPage } from './notebook-detail';

describe('NotebookDetailPage', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  function create(messages = [QUESTION, ANSWER], notes = [CONSENSUS_NOTE]) {
    const fixture = TestBed.createComponent(NotebookDetailPage);
    fixture.componentRef.setInput('id', 'nb1');
    fixture.detectChanges();
    http.expectOne('/api/v1/notebooks/nb1').flush(NOTEBOOK);
    http.expectOne('/api/v1/notebooks/nb1/messages').flush(messages);
    http.expectOne('/api/v1/notebooks/nb1/notes').flush(notes);
    fixture.detectChanges();
    return fixture;
  }

  function button(fixture: { nativeElement: HTMLElement }, text: string): HTMLButtonElement {
    return [...fixture.nativeElement.querySelectorAll('button')].find((b) => b.textContent?.trim().startsWith(text)) as HTMLButtonElement;
  }

  it('shows sources with their index state and the saved conversation with citation buttons', () => {
    const fixture = create();
    const text: string = fixture.nativeElement.textContent;
    expect(text).toContain('1 of 2 searchable');
    expect(text).toContain('Not indexed');
    expect(text).toContain('TCBS khuyến nghị gì?');
    expect(text).toContain('Supported by sources');
    const cite: HTMLButtonElement = fixture.nativeElement.querySelector('button.cite');
    expect(cite.textContent).toBe('S1');
    // The answer is rendered as text, never as HTML.
    expect(fixture.nativeElement.querySelector('.turn__text').innerHTML).not.toContain('[S1]');
    cite.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('#cite-a1-S1').classList).toContain('source--active');
  });

  it('asks a question and appends both messages', async () => {
    const fixture = create([], []);
    await fixture.whenStable();
    const textarea: HTMLTextAreaElement = fixture.nativeElement.querySelector('textarea[name="question"]');
    textarea.value = 'TCBS khuyến nghị gì?';
    textarea.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    fixture.nativeElement.querySelector('form.nb-ask').dispatchEvent(new Event('submit'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Searching sources and writing an answer');

    const request = http.expectOne('/api/v1/notebooks/nb1/ask');
    expect(request.request.body).toEqual({ question: 'TCBS khuyến nghị gì?' });
    request.flush({ question: QUESTION, answer: ANSWER, coverageGaps: [], disclaimer: 'x' });
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('.nb-question').length).toBe(1);
    expect(fixture.nativeElement.textContent).toContain('Theo TCBS, khuyến nghị Mua');
  });

  it('renders a consensus note with stored ratings, limitations and deleted sources', () => {
    const fixture = create();
    button(fixture, 'Notes').click();
    fixture.detectChanges();
    const note: HTMLElement = fixture.nativeElement.querySelector('[data-note="n1"]');
    const text = note.textContent ?? '';
    expect(text).toContain('Consensus and disagreement');
    expect(text).toContain('SCS');
    expect(text).toContain('Bán');
    expect(text).toContain('Where sources differ');
    expect(text).toContain('Limitations (1)');
    expect(text).toContain('Source deleted');
    expect(text).toContain('not Investary recommendations');
  });

  it('requests a note for the chosen ticker and polls until it is done', async () => {
    const fixture = create([], []);
    button(fixture, 'Notes').click();
    fixture.detectChanges();
    await fixture.whenStable();
    vi.useFakeTimers();
    const select: HTMLSelectElement = fixture.nativeElement.querySelector('select[data-ticker-for="TIMELINE"]');
    select.value = 'SCS';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    const generate = [...fixture.nativeElement.querySelectorAll('.nb-card')][2].querySelector('button') as HTMLButtonElement;
    expect(generate.disabled).toBe(false);
    generate.click();

    const request = http.expectOne('/api/v1/notebooks/nb1/notes');
    expect(request.request.body).toEqual({ kind: 'TIMELINE', ticker: 'SCS' });
    request.flush({ ...CONSENSUS_NOTE, id: 'n2', kind: 'TIMELINE', status: 'PENDING', content: null, citations: [], limitations: [] });
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Waiting to be generated');

    vi.advanceTimersByTime(3000);
    http.expectOne('/api/v1/notebooks/nb1/notes').flush([{ ...CONSENSUS_NOTE, id: 'n2', kind: 'TIMELINE' }]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('Timeline of views');
    vi.advanceTimersByTime(3000);
    http.expectNone('/api/v1/notebooks/nb1/notes');
  });

  it('adds picked documents', () => {
    const fixture = create([], []);
    button(fixture, 'Add sources').click();
    http.expectOne('/api/v1/documents?page=0&size=50&sort=uploadedAt,desc').flush({
      content: [
        { id: 'd1', originalFilename: 'TCBS.pdf', brokerageCompany: 'TCBS', ticker: 'SCS', publishedOn: null },
        { id: 'd2', originalFilename: 'VND.pdf', brokerageCompany: 'VNDIRECT', ticker: 'FPT', publishedOn: null },
      ],
      page: 0,
      size: 50,
      totalElements: 2,
      totalPages: 1,
    });
    http.expectOne((r) => r.url === '/api/v1/emails').flush({ content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 });
    fixture.detectChanges();

    const boxes: HTMLInputElement[] = [...fixture.nativeElement.querySelectorAll('.picker__list input[type="checkbox"]')];
    expect(boxes[0].disabled).toBe(true); // already in the notebook
    boxes[1].click();
    fixture.detectChanges();
    button(fixture, 'Add 1 selected').click();

    const request = http.expectOne('/api/v1/notebooks/nb1/sources');
    expect(request.request.body).toEqual({ documentIds: ['d2'] });
    request.flush(NOTEBOOK);
  });
});
