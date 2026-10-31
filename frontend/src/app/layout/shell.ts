import { Component, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

@Component({
  selector: 'app-shell',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="topbar">
      <a routerLink="/" class="brand" aria-label="Investary home">
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
      >
        Menu
      </button>
      <nav id="main-nav" class="nav" [class.nav--open]="menuOpen()" (click)="menuOpen.set(false)">
        <a routerLink="/dashboard" routerLinkActive="nav__link--active" class="nav__link">Dashboard</a>
        <a routerLink="/documents" routerLinkActive="nav__link--active" class="nav__link">Document library</a>
        <a routerLink="/emails" routerLinkActive="nav__link--active" class="nav__link">Email research</a>
        <a routerLink="/portfolios" routerLinkActive="nav__link--active" class="nav__link">Portfolios</a>
        <a routerLink="/profile" routerLinkActive="nav__link--active" class="nav__link">Profile</a>
        <a routerLink="/assistant" routerLinkActive="nav__link--active" class="nav__link">Research assistant</a>
      <a routerLink="/automation" routerLinkActive="nav__link--active" class="nav__link">Gmail sync</a>
      </nav>
    </header>
    <main class="content">
      <router-outlet />
    </main>
    <footer class="footer">Investary · Phase 5 · local single-user mode, no authentication · not investment advice</footer>
  `,
})
export class Shell {
  protected readonly menuOpen = signal(false);
}
