import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: $localize`:@@title.dashboard:Dashboard · Investary`,
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'documents',
    title: $localize`:@@title.documentLibrary:Document library · Investary`,
    loadComponent: () => import('./features/documents/document-library').then((m) => m.DocumentLibrary),
  },
  {
    path: 'documents/:id',
    title: $localize`:@@title.document:Document · Investary`,
    loadComponent: () => import('./features/documents/document-detail').then((m) => m.DocumentDetail),
  },
  {
    path: 'emails',
    title: $localize`:@@title.emailResearch:Email research · Investary`,
    loadComponent: () => import('./features/emails/email-inbox').then((m) => m.EmailInbox),
  },
  {
    path: 'emails/:id',
    title: $localize`:@@title.email:Email · Investary`,
    loadComponent: () => import('./features/emails/email-detail').then((m) => m.EmailDetail),
  },
  {
    path: 'assistant',
    title: $localize`:@@title.researchAssistant:Research assistant · Investary`,
    loadComponent: () => import('./features/assistant/research-assistant').then((m) => m.ResearchAssistant),
  },
  {
    path: 'notebooks',
    title: $localize`:@@title.notebooks:Research notebooks · Investary`,
    loadComponent: () => import('./features/notebooks/notebook-list').then((m) => m.NotebookList),
  },
  {
    path: 'notebooks/:id',
    title: $localize`:@@title.notebook:Notebook · Investary`,
    loadComponent: () => import('./features/notebooks/notebook-detail').then((m) => m.NotebookDetailPage),
  },
  {
    path: 'portfolios',
    title: $localize`:@@title.portfolios:Portfolios · Investary`,
    loadComponent: () => import('./features/portfolio/portfolio-list').then((m) => m.PortfolioList),
  },
  {
    path: 'portfolios/:id',
    title: $localize`:@@title.portfolio:Portfolio · Investary`,
    loadComponent: () => import('./features/portfolio/portfolio-dashboard').then((m) => m.PortfolioDashboard),
  },
  {
    path: 'profile',
    title: $localize`:@@title.investorProfile:Investor profile · Investary`,
    loadComponent: () => import('./features/investor/investor-profile').then((m) => m.InvestorProfilePage),
  },
  {
    path: 'briefs',
    title: $localize`:@@title.dailyBrief:Daily brief · Investary`,
    loadComponent: () => import('./features/briefs/brief-list').then((m) => m.BriefList),
  },
  {
    path: 'briefs/:id',
    title: $localize`:@@title.brief:Brief · Investary`,
    loadComponent: () => import('./features/briefs/brief-detail').then((m) => m.BriefDetail),
  },
  {
    path: 'automation',
    loadComponent: () => import('./features/automation/automation-center').then((m) => m.AutomationCenter),
    children: [
      {
        path: '',
        title: $localize`:@@title.automationCenter:Automation Center · Investary`,
        loadComponent: () => import('./features/automation/automation-overview').then((m) => m.AutomationOverview),
      },
      {
        path: 'sources',
        title: $localize`:@@title.sourcesAutomationCenter:Sources · Automation Center · Investary`,
        loadComponent: () => import('./features/automation/source-rules').then((m) => m.SourceRules),
      },
      {
        path: 'jobs',
        title: $localize`:@@title.jobsAutomationCenter:Jobs · Automation Center · Investary`,
        loadComponent: () => import('./features/automation/job-history').then((m) => m.JobHistory),
      },
      {
        path: 'jobs/:id',
        title: $localize`:@@title.jobAutomationCenter:Job · Automation Center · Investary`,
        loadComponent: () => import('./features/automation/job-detail').then((m) => m.JobDetailPage),
      },
      {
        path: 'settings',
        title: $localize`:@@title.settingsAutomationCenter:Settings · Automation Center · Investary`,
        loadComponent: () => import('./features/automation/automation-settings').then((m) => m.AutomationSettingsPage),
      },
      {
        path: 'usage',
        title: $localize`:@@title.aiUsageAutomationCenter:AI usage · Automation Center · Investary`,
        loadComponent: () => import('./features/automation/ai-usage').then((m) => m.AiUsagePage),
      },
    ],
  },
  {
    path: 'journal',
    title: $localize`:@@title.decisionJournal:Decision journal · Investary`,
    loadComponent: () => import('./features/journal/decision-list').then((m) => m.DecisionList),
  },
  {
    path: 'journal/new',
    title: $localize`:@@title.newDecision:New decision · Investary`,
    loadComponent: () => import('./features/journal/decision-form').then((m) => m.DecisionForm),
  },
  {
    path: 'journal/:id',
    title: $localize`:@@title.decision:Decision · Investary`,
    loadComponent: () => import('./features/journal/decision-detail').then((m) => m.DecisionDetail),
  },
  {
    path: 'journal/:id/edit',
    title: $localize`:@@title.editDecision:Edit decision · Investary`,
    loadComponent: () => import('./features/journal/decision-form').then((m) => m.DecisionForm),
  },
  {
    path: 'notifications',
    title: $localize`:@@title.notifications:Notifications · Investary`,
    loadComponent: () => import('./features/notifications/notification-list').then((m) => m.NotificationList),
  },
  { path: '**', redirectTo: 'dashboard' },
];
