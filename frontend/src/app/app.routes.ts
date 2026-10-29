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
  { path: '**', redirectTo: 'dashboard' },
];
