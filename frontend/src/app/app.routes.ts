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
  { path: '**', redirectTo: 'dashboard' },
];
