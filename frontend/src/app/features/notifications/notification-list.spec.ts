import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { notification } from '../../testing/automation-fixtures';
import { NotificationList } from './notification-list';

describe('NotificationList', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [NotificationList],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render() {
    const fixture = TestBed.createComponent(NotificationList);
    fixture.detectChanges();
    http.expectOne('/api/v1/notifications?page=0&size=20').flush({
      content: [
        notification({ priority: 'HIGH', type: 'RESEARCH_RISK', title: 'Possible risk for IMP', entityType: 'ASSESSMENT', entityId: 'a1' }),
        notification({ id: 'n2', type: 'PROCESSING_FAILED', title: 'Processing failed: IMP', entityType: 'JOB', entityId: 'j1', read: true }),
      ],
      page: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
    });
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('lists notifications with priority, type and links to what they refer to', async () => {
    const fixture = await render();
    const items = (fixture.nativeElement as HTMLElement).querySelectorAll('.notification-item');
    expect(items[0].textContent).toContain('High');
    expect(items[0].textContent).toContain('Possible risk');
    expect(items[0].querySelector('a')?.getAttribute('href')).toBe('/portfolios/p1');
    expect(items[1].querySelector('a')?.getAttribute('href')).toBe('/automation/jobs/j1');
  });

  it('toggles read state and filters unread notifications', async () => {
    const fixture = await render();
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelectorAll('.notification-item .link-button')[1] as HTMLButtonElement).click();
    http.expectOne({ method: 'PATCH', url: '/api/v1/notifications/n2/unread' }).flush(notification({ id: 'n2', read: false }));
    fixture.detectChanges();
    expect(el.querySelectorAll('.notification-item--unread')).toHaveLength(2);

    const c = fixture.componentInstance as unknown as { unreadOnly: boolean; type: string; load: (page: number) => void };
    c.unreadOnly = true;
    c.type = 'RESEARCH_RISK';
    c.load(0);
    http.expectOne('/api/v1/notifications?page=0&size=20&unreadOnly=true&type=RESEARCH_RISK')
      .flush({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    fixture.detectChanges();
    expect(el.textContent).toContain('No notifications');
  });
});
