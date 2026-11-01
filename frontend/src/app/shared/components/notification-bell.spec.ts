import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { notification } from '../../testing/automation-fixtures';
import { NotificationBell } from './notification-bell';


describe('NotificationBell', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      imports: [NotificationBell],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    vi.useRealTimers();
    http.verify();
  });

  function render(count: number) {
    const fixture = TestBed.createComponent(NotificationBell);
    http.expectOne('/api/v1/notifications/unread-count').flush({ count });
    fixture.detectChanges();
    return fixture;
  }

  it('shows the unread count and refreshes it every minute', () => {
    const fixture = render(3);
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('[data-testid=unread-count]')?.textContent).toBe('3');
    expect(el.querySelector('button')?.getAttribute('aria-label')).toBe('Notifications, 3 unread');

    vi.advanceTimersByTime(60_000);
    http.expectOne('/api/v1/notifications/unread-count').flush({ count: 0 });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=unread-count]')).toBeNull();
  });

  it('opens the latest notifications with links and marks one read', () => {
    const fixture = render(1);
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('.bell__button') as HTMLButtonElement).click();
    http.expectOne('/api/v1/notifications?page=0&size=10').flush({ content: [notification(),
      notification({ id: 'n2', type: 'DAILY_BRIEF_READY', title: 'Daily brief for 2026-11-01 is ready', entityType: 'BRIEF', entityId: 'b1',
        read: true })], page: 0, size: 10, totalElements: 2, totalPages: 1 });
    fixture.detectChanges();

    const items = el.querySelectorAll('.notification-item');
    expect(items).toHaveLength(2);
    expect(items[0].classList).toContain('notification-item--unread');
    expect(items[0].querySelector('a')?.getAttribute('href')).toBe('/emails/e1');
    expect(items[1].querySelector('a')?.getAttribute('href')).toBe('/briefs/b1');

    (items[0].querySelector('.notification-item__meta button') as HTMLButtonElement).click();
    http.expectOne({ method: 'PATCH', url: '/api/v1/notifications/n1/read' }).flush(notification({ read: true, readAt: '2026-11-01T03:00:00Z' }));
    http.expectOne('/api/v1/notifications/unread-count').flush({ count: 0 });
    fixture.detectChanges();
    expect(el.querySelectorAll('.notification-item--unread')).toHaveLength(0);
  });

  it('marks everything read', () => {
    const fixture = render(2);
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('.bell__button') as HTMLButtonElement).click();
    http.expectOne('/api/v1/notifications?page=0&size=10').flush({ content: [notification()], page: 0, size: 10, totalElements: 1, totalPages: 1 });
    fixture.detectChanges();
    (el.querySelector('.bell__head button') as HTMLButtonElement).click();
    http.expectOne({ method: 'POST', url: '/api/v1/notifications/read-all' }).flush({ updated: 2 });
    fixture.detectChanges();
    expect(el.querySelector('[data-testid=unread-count]')).toBeNull();
  });
});
