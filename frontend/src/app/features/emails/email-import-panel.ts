import { HttpEventType } from '@angular/common/http';
import { Component, ElementRef, inject, output, signal, viewChild } from '@angular/core';

import { EmailApiService } from '../../core/api/email-api.service';
import { errorMessage } from '../../core/api/error-message';
import { EmailDetail } from '../../core/models/email.model';
import { Alert } from '../../shared/components/alert';
import { FileSizePipe } from '../../shared/pipes/file-size.pipe';

/** Mirrors the backend default (INVESTARY_EMAIL_MAX_SIZE); the server remains the source of truth. */
export const MAX_EML_BYTES = 25 * 1024 * 1024;

@Component({
  selector: 'app-email-import-panel',
  imports: [Alert, FileSizePipe],
  template: `
    <section class="card upload">
      <div class="card__header">
        <h2 class="card__title">Import email</h2>
        <span class="muted">.eml only · max {{ maxBytes | fileSize }}</span>
      </div>
      <label
        class="dropzone"
        [class.dropzone--active]="dragging()"
        [class.dropzone--selected]="file()"
        (dragover)="$event.preventDefault(); dragging.set(true)"
        (dragleave)="dragging.set(false)"
        (drop)="onDrop($event)"
      >
        <input #fileInput type="file" accept=".eml,message/rfc822" class="visually-hidden" (change)="onFileSelected($event)" />
        @if (file(); as selected) {
          <span class="dropzone__file">{{ selected.name }}</span>
          <span class="muted">{{ selected.size | fileSize }}</span>
        } @else {
          <span class="dropzone__hint"><strong>Choose an .eml file</strong> or drag it here</span>
          <span class="muted">Export a brokerage email from your mail client ("Save as" / "Download message").</span>
        }
      </label>

      @if (error(); as message) {
        <app-alert kind="error" [dismissible]="true" (dismissed)="error.set(null)">
          {{ message }}
          @if (existingId(); as id) {
            <a class="link" [href]="'/emails/' + id">Open the existing email</a>
          }
        </app-alert>
      }
      @if (progress() !== null) {
        <div class="progress" role="progressbar" [attr.aria-valuenow]="progress()" aria-valuemin="0" aria-valuemax="100">
          <div class="progress__bar" [style.width.%]="progress()"></div>
        </div>
      }
      <div class="actions">
        @if (file()) {
          <button type="button" class="btn btn--ghost" (click)="clear()" [disabled]="progress() !== null">Clear</button>
        }
        <button type="button" class="btn btn--primary" [disabled]="!file() || progress() !== null" (click)="submit()">
          {{ progress() !== null ? (progress() === 100 ? 'Parsing…' : 'Uploading… ' + progress() + '%') : 'Import' }}
        </button>
      </div>
    </section>
  `,
})
export class EmailImportPanel {
  private readonly api = inject(EmailApiService);
  private readonly fileInput = viewChild.required<ElementRef<HTMLInputElement>>('fileInput');

  readonly imported = output<EmailDetail>();

  protected readonly maxBytes = MAX_EML_BYTES;
  protected readonly file = signal<File | null>(null);
  protected readonly progress = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly existingId = signal<string | null>(null);
  protected readonly dragging = signal(false);

  protected onFileSelected(event: Event): void {
    this.select((event.target as HTMLInputElement).files?.[0] ?? null);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    this.select(event.dataTransfer?.files?.[0] ?? null);
  }

  protected submit(): void {
    const file = this.file();
    if (!file || this.progress() !== null) {
      return;
    }
    this.error.set(null);
    this.existingId.set(null);
    this.progress.set(0);
    this.api.import(file).subscribe({
      next: (event) => {
        if (event.type === HttpEventType.UploadProgress && event.total) {
          this.progress.set(Math.round((100 * event.loaded) / event.total));
        } else if (event.type === HttpEventType.Response && event.body) {
          this.progress.set(null);
          this.clear();
          this.imported.emit(event.body);
        }
      },
      error: (err) => {
        this.progress.set(null);
        this.error.set(errorMessage(err, 'Import failed.'));
        this.existingId.set(err?.error?.existingEmailId ?? null);
      },
    });
  }

  protected clear(): void {
    this.file.set(null);
    this.fileInput().nativeElement.value = '';
  }

  private select(file: File | null): void {
    this.error.set(null);
    this.existingId.set(null);
    if (!file) {
      return;
    }
    if (!file.name.toLowerCase().endsWith('.eml') && file.type !== 'message/rfc822') {
      this.error.set('Please choose an .eml file.');
      return;
    }
    if (file.size > MAX_EML_BYTES) {
      this.error.set('The file is larger than the 25 MB import limit.');
      return;
    }
    this.file.set(file);
  }
}
