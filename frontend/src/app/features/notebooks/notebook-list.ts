import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';

import { errorMessage } from '../../core/api/error-message';
import { NotebookApiService } from '../../core/api/notebook-api.service';
import { NotebookSummary } from '../../core/models/notebook.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';

/** Research notebooks: each one is a picked set of documents and emails to chat with and summarise. */
@Component({
  selector: 'app-notebook-list',
  imports: [DatePipe, FormsModule, RouterLink, Alert, EmptyState],
  templateUrl: './notebook-list.html',
})
export class NotebookList {
  private readonly api = inject(NotebookApiService);
  private readonly router = inject(Router);

  protected readonly notebooks = signal<NotebookSummary[] | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly creating = signal(false);
  protected title = '';
  protected description = '';

  constructor() {
    this.api.list().subscribe({
      next: (list) => this.notebooks.set(list),
      error: (err) => this.error.set(errorMessage(err, $localize`:@@notebookList.error.load:Could not load notebooks.`)),
    });
  }

  protected create(): void {
    const title = this.title.trim();
    if (!title || this.creating()) {
      return;
    }
    this.creating.set(true);
    this.error.set(null);
    this.api.create(title, this.description.trim() || null).subscribe({
      next: (notebook) => this.router.navigate(['/notebooks', notebook.id]),
      error: (err) => {
        this.creating.set(false);
        this.error.set(errorMessage(err, $localize`:@@notebookList.error.create:Could not create the notebook.`));
      },
    });
  }
}
