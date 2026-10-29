import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { EmailApiService } from './email-api.service';

describe('EmailApiService', () => {
  let service: EmailApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    service = TestBed.inject(EmailApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists emails with search, sender and paging params', () => {
    service.list({ q: '  Cơ hội ', sender: 'noreply@tcbs.com.vn', page: 1, size: 10 }).subscribe();

    const req = http.expectOne((r) => r.url === '/api/v1/emails');
    expect(req.request.params.get('q')).toBe('Cơ hội');
    expect(req.request.params.get('sender')).toBe('noreply@tcbs.com.vn');
    expect(req.request.params.get('page')).toBe('1');
    expect(req.request.params.get('size')).toBe('10');
    expect(req.request.params.get('sort')).toBe('importedAt,desc');
    req.flush({ content: [], page: 1, size: 10, totalElements: 0, totalPages: 0 });
  });

  it('omits empty filters', () => {
    service.list({ q: '   ' }).subscribe();

    const req = http.expectOne((r) => r.url === '/api/v1/emails');
    expect(req.request.params.has('q')).toBe(false);
    expect(req.request.params.has('sender')).toBe(false);
    req.flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  });

  it('imports an EML file as multipart form data', () => {
    const file = new File(['From: a@example.com\n\nx'], 'newsletter.eml', { type: 'message/rfc822' });

    service.import(file).subscribe();

    const req = http.expectOne('/api/v1/emails/import');
    expect(req.request.method).toBe('POST');
    expect(((req.request.body as FormData).get('file') as File).name).toBe('newsletter.eml');
    req.flush({ id: '1' });
  });

  it('requests a link download', () => {
    service.requestDownload('e1', 'l1').subscribe();

    const req = http.expectOne('/api/v1/emails/e1/links/l1/download');
    expect(req.request.method).toBe('POST');
    req.flush({ id: 'l1', status: 'DOWNLOADING' }, { status: 202, statusText: 'Accepted' });
  });

  it('builds preview and original URLs', () => {
    expect(service.htmlUrl('e1')).toBe('/api/v1/emails/e1/html');
    expect(service.originalUrl('e1')).toBe('/api/v1/emails/e1/original');
  });
});
