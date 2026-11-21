import { Component, LOCALE_ID, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { filter, map } from 'rxjs';

import { alternateLanguageUrl, otherLanguage, toLanguage } from '../core/i18n/language';
import { NotificationBell } from '../shared/components/notification-bell';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, NotificationBell],
  template: `
    <header class="topbar">
      <a routerLink="/" class="brand" aria-label="Investary home" i18n-aria-label="@@shell.home">
        <span class="brand__mark" aria-hidden="true">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2.2">
            <path d="M4 17l5-5 4 4 7-8" />
          </svg>
        </span>
        <span class="brand__name">Investary</span>
      </a>
      <button
        type="button"
        class="topbar__toggle"
        [attr.aria-expanded]="menuOpen()"
        aria-controls="main-nav"
        (click)="menuOpen.set(!menuOpen())"
        i18n="@@shell.menu"
      >
        Menu
      </button>
      <nav id="main-nav" class="nav" [class.nav--open]="menuOpen()" (click)="menuOpen.set(false)">
        <a routerLink="/dashboard" routerLinkActive="nav__link--active" class="nav__link" i18n="@@nav.dashboard">Dashboard</a>
        <a routerLink="/documents" routerLinkActive="nav__link--active" class="nav__link" i18n="@@nav.documents">Document library</a>
        <a routerLink="/emails" routerLinkActive="nav__link--active" class="nav__link" i18n="@@nav.emails">Email research</a>
        <a routerLink="/portfolios" routerLinkActive="nav__link--active" class="nav__link" i18n="@@nav.portfolios">Portfolios</a>
        <a routerLink="/journal" routerLinkActive="nav__link--active" class="nav__link" i18n="@@nav.journal">Journal</a>
        <a routerLink="/profile" routerLinkActive="nav__link--active" class="nav__link" i18n="@@nav.profile">Profile</a>
        <a routerLink="/briefs" routerLinkActive="nav__link--active" class="nav__link" i18n="@@nav.briefs">Daily brief</a>
        <a routerLink="/assistant" routerLinkActive="nav__link--active" class="nav__link" i18n="@@nav.assistant">Research assistant</a>
        <a routerLink="/automation" routerLinkActive="nav__link--active" class="nav__link" i18n="@@nav.automation">Automation</a>
      </nav>
      <div class="lang-switch" role="group" aria-label="Language" i18n-aria-label="@@shell.language">
        @for (option of languages; track option) {
          @if (option === language) {
            <span class="lang-switch__option lang-switch__option--active" aria-current="true">{{ option.toUpperCase() }}</span>
          } @else {
            <a
              class="lang-switch__option"
              [href]="alternateUrl()"
              [attr.hreflang]="option"
              [attr.lang]="option"
              [attr.aria-label]="switchLabel"
              >{{ option.toUpperCase() }}</a
            >
          }
        }
      </div>
      <app-notification-bell />
    </header>
    <main class="content">
      <router-outlet />
    </main>
    <footer class="footer" i18n="@@shell.footer">
      Investary · Phase 5 · local single-user mode, no authentication · not investment advice
    </footer>
  `,
})
export class Shell {
  private readonly router = inject(Router);

  protected readonly menuOpen = signal(false);
  protected readonly language = toLanguage(inject(LOCALE_ID));
  protected readonly languages = ['vi', 'en'] as const;
  protected readonly switchLabel =
    otherLanguage(this.language) === 'en'
      ? $localize`:@@shell.switchToEnglish:Switch to English`
      : $localize`:@@shell.switchToVietnamese:Switch to Vietnamese`;

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  protected readonly alternateUrl = computed(() => alternateLanguageUrl(this.language, this.url()));
}
