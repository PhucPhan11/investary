import { Routes } from '@angular/router';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'dashboard' },
  {
    path: 'dashboard',
    title: 'Dashboard · Investary',
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'documents',
    title: 'Document library · Investary',
    loadComponent: () => import('./features/documents/document-library').then((m) => m.DocumentLibrary),
  },
  {
    path: 'documents/:id',
    title: 'Document · Investary',
    loadComponent: () => import('./features/documents/document-detail').then((m) => m.DocumentDetail),
  },
  {
    path: 'emails',
    title: 'Email research · Investary',
    loadComponent: () => import('./features/emails/email-inbox').then((m) => m.EmailInbox),
  },
  {
    path: 'emails/:id',
    title: 'Email · Investary',
    loadComponent: () => import('./features/emails/email-detail').then((m) => m.EmailDetail),
  },
  {
    path: 'assistant',
    title: 'Research assistant · Investary',
    loadComponent: () => import('./features/assistant/research-assistant').then((m) => m.ResearchAssistant),
  },
  {
    path: 'portfolios',
    title: 'Portfolios · Investary',
    loadComponent: () => import('./features/portfolio/portfolio-list').then((m) => m.PortfolioList),
  },
  {
    path: 'portfolios/:id',
    title: 'Portfolio · Investary',
    loadComponent: () => import('./features/portfolio/portfolio-dashboard').then((m) => m.PortfolioDashboard),
  },
  {
    path: 'profile',
    title: 'Investor profile · Investary',
    loadComponent: () => import('./features/investor/investor-profile').then((m) => m.InvestorProfilePage),
  },
  {
    path: 'briefs',
    title: 'Daily brief · Investary',
    loadComponent: () => import('./features/briefs/brief-list').then((m) => m.BriefList),
  },
  {
    path: 'briefs/:id',
    title: 'Brief · Investary',
    loadComponent: () => import('./features/briefs/brief-detail').then((m) => m.BriefDetail),
  },
  {
    path: 'automation',
    loadComponent: () => import('./features/automation/automation-center').then((m) => m.AutomationCenter),
    children: [
      {
        path: '',
        title: 'Automation Center · Investary',
        loadComponent: () => import('./features/automation/automation-overview').then((m) => m.AutomationOverview),
      },
      {
        path: 'sources',
        title: 'Sources · Automation Center · Investary',
        loadComponent: () => import('./features/automation/source-rules').then((m) => m.SourceRules),
      },
      {
        path: 'jobs',
        title: 'Jobs · Automation Center · Investary',
        loadComponent: () => import('./features/automation/job-history').then((m) => m.JobHistory),
      },
      {
        path: 'jobs/:id',
        title: 'Job · Automation Center · Investary',
        loadComponent: () => import('./features/automation/job-detail').then((m) => m.JobDetailPage),
      },
      {
        path: 'settings',
        title: 'Settings · Automation Center · Investary',
        loadComponent: () => import('./features/automation/automation-settings').then((m) => m.AutomationSettingsPage),
      },
      {
        path: 'usage',
        title: 'AI usage · Automation Center · Investary',
        loadComponent: () => import('./features/automation/ai-usage').then((m) => m.AiUsagePage),
      },
    ],
  },
  {
    path: 'notifications',
    title: 'Notifications · Investary',
    loadComponent: () => import('./features/notifications/notification-list').then((m) => m.NotificationList),
  },
  { path: '**', redirectTo: 'dashboard' },
];
