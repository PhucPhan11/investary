import { Component } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

/**
 * Automation Center: one place to observe and control Gmail sync, source rules, research-processing jobs,
 * runtime settings and AI usage. Every figure comes from the backend.
 */
@Component({
  selector: 'app-automation-center',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  template: `
    <header class="page-header">
      <div>
        <h1 class="page-title">Automation Center</h1>
        <p class="page-subtitle">
          Gmail import, research processing, daily briefs and AI limits. Local-only; nothing here is investment advice.
        </p>
      </div>
    </header>
    <nav class="subnav" aria-label="Automation Center">
      <a routerLink="/automation" [routerLinkActiveOptions]="{ exact: true }" routerLinkActive="subnav__link--active" class="subnav__link">
        Overview
      </a>
      <a routerLink="/automation/sources" routerLinkActive="subnav__link--active" class="subnav__link">Sources</a>
      <a routerLink="/automation/jobs" routerLinkActive="subnav__link--active" class="subnav__link">Jobs</a>
      <a routerLink="/automation/settings" routerLinkActive="subnav__link--active" class="subnav__link">Settings</a>
      <a routerLink="/automation/usage" routerLinkActive="subnav__link--active" class="subnav__link">AI usage</a>
    </nav>
    <router-outlet />
  `,
})
export class AutomationCenter {}
