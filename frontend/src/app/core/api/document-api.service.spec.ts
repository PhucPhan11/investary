import { HttpEventType, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { DocumentApiService } from './document-api.service';

describe('DocumentApiService', () => {
  let service: DocumentApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(DocumentApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists documents newest first with paging params', () => {
    service.list(2, 10).subscribe((page) => expect(page.totalElements).toBe(0));

    const req = http.expectOne((r) => r.url === '/api/v1/documents');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('page')).toBe('2');
    expect(req.request.params.get('size')).toBe('10');
    expect(req.request.params.get('sort')).toBe('uploadedAt,desc');
    req.flush({ content: [], page: 2, size: 10, totalElements: 0, totalPages: 0 });
  });

  it('uploads multipart form data and skips blank metadata', () => {
    const file = new File(['%PDF-1.4'], 'report.pdf', { type: 'application/pdf' });
    const types: HttpEventType[] = [];

    service
      .upload(file, { brokerageCompany: '  SSI  ', ticker: '', reportType: 'STOCK_ANALYSIS' })
      .subscribe((event) => types.push(event.type));

    const req = http.expectOne('/api/v1/documents');
    expect(req.request.method).toBe('POST');
    const body = req.request.body as FormData;
    expect((body.get('file') as File).name).toBe('report.pdf');
    expect(body.get('brokerageCompany')).toBe('SSI');
    expect(body.get('reportType')).toBe('STOCK_ANALYSIS');
    expect(body.has('ticker')).toBe(false);
    req.flush({ id: '1' });
    expect(types).toContain(HttpEventType.Response);
  });

  it('deletes by id', () => {
    service.delete('abc').subscribe();

    const req = http.expectOne('/api/v1/documents/abc');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('builds the download url', () => {
    expect(service.downloadUrl('abc')).toBe('/api/v1/documents/abc/download');
  });
});
