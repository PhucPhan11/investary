import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { App } from './app';

describe('App', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
  });

  it('renders the shell with brand and navigation', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelector('.brand__name')?.textContent).toContain('Investary');
    const links = Array.from(el.querySelectorAll('.nav__link')).map((a) => a.textContent?.trim());
    expect(el.querySelector('app-notification-bell')).not.toBeNull();
    expect(links).toEqual(['Dashboard', 'Document library', 'Email research', 'Portfolios', 'Journal', 'Profile', 'Daily brief', 'Research assistant', 'Notebooks', 'Automation']);
  });

  it('shows the current language and links to the other build', async () => {
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    const el = fixture.nativeElement as HTMLElement;
    // Tests run in the source locale (en).
    expect(el.querySelector('.lang-switch__option--active')?.textContent?.trim()).toBe('EN');
    const link = el.querySelector('a.lang-switch__option') as HTMLAnchorElement;
    expect(link.textContent?.trim()).toBe('VI');
    expect(link.getAttribute('hreflang')).toBe('vi');
    expect(link.getAttribute('href')).toBe('/vi/');
    expect(link.getAttribute('aria-label')).toBe('Switch to Vietnamese');
  });
});
