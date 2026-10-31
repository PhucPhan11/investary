import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { GmailApiService, SourceRuleApiService } from './automation-api.service';

describe('Gmail and source rule API services', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('uses the Gmail integration endpoints', () => {
    const gmail = TestBed.inject(GmailApiService);
    gmail.status().subscribe();
    http.expectOne('/api/v1/integrations/gmail/status').flush({});
    gmail.connect().subscribe();
    expect(http.expectOne('/api/v1/integrations/gmail/connect').request.method).toBe('POST');
    gmail.sync().subscribe();
    expect(http.expectOne('/api/v1/integrations/gmail/sync').request.method).toBe('POST');
    gmail.disconnect().subscribe();
    expect(http.expectOne('/api/v1/integrations/gmail/disconnect').request.method).toBe('POST');
    gmail.history(1, 5).subscribe();
    http.expectOne('/api/v1/integrations/gmail/sync-history?page=1&size=5').flush({ content: [] });
  });

  it('uses the source rule endpoints', () => {
    const rules = TestBed.inject(SourceRuleApiService);
    rules.list().subscribe();
    http.expectOne('/api/v1/automation/sources').flush([]);
    rules.update('r 1', {
      name: 'TCBS',
      senderAddresses: [],
      senderDomains: ['tcbs.com.vn'],
      subjectKeywords: [],
      gmailQuery: null,
      enabled: true,
      autoImport: true,
      autoPdfDownload: false,
    }).subscribe();
    expect(http.expectOne('/api/v1/automation/sources/r%201').request.method).toBe('PUT');
    rules.delete('r1').subscribe();
    expect(http.expectOne('/api/v1/automation/sources/r1').request.method).toBe('DELETE');
  });
});
