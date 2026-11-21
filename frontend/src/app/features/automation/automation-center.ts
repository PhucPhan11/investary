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
        <h1 class="page-title" i18n="@@automationCenter.automationCenter">Automation Center</h1>
        <p class="page-subtitle" i18n="@@automationCenter.gmailImportResearchProcessing">
          Gmail import, research processing, daily briefs and AI limits. Local-only; nothing here is investment advice.
        </p>
      </div>
    </header>
    <nav class="subnav" aria-label="Automation Center" i18n-aria-label="@@automationCenter.automationCenter">
      <a routerLink="/automation" [routerLinkActiveOptions]="{ exact: true }" routerLinkActive="subnav__link--active" class="subnav__link" i18n="@@automationCenter.overview">
        Overview
      </a>
      <a routerLink="/automation/sources" routerLinkActive="subnav__link--active" class="subnav__link" i18n="@@automationCenter.sources">Sources</a>
      <a routerLink="/automation/jobs" routerLinkActive="subnav__link--active" class="subnav__link" i18n="@@automationCenter.jobs">Jobs</a>
      <a routerLink="/automation/settings" routerLinkActive="subnav__link--active" class="subnav__link" i18n="@@automationCenter.settings">Settings</a>
      <a routerLink="/automation/usage" routerLinkActive="subnav__link--active" class="subnav__link" i18n="@@automationCenter.aiUsage">AI usage</a>
    </nav>
    <router-outlet />
  `,
})
export class AutomationCenter {}
