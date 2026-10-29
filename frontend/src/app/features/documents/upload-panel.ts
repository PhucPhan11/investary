import { HttpEventType } from '@angular/common/http';
import { Component, ElementRef, inject, output, signal, viewChild } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';

import { DocumentApiService } from '../../core/api/document-api.service';
import { errorMessage } from '../../core/api/error-message';
import { REPORT_TYPES, ReportType, ResearchDocument } from '../../core/models/document.model';
import { Alert } from '../../shared/components/alert';
import { FileSizePipe } from '../../shared/pipes/file-size.pipe';
import { REPORT_TYPE_LABELS } from '../../shared/pipes/report-type-label.pipe';

/** Mirrors the backend default (INVESTARY_MAX_UPLOAD_SIZE); the server remains the source of truth. */
export const MAX_UPLOAD_BYTES = 50 * 1024 * 1024;

@Component({
  selector: 'app-upload-panel',
  imports: [ReactiveFormsModule, Alert, FileSizePipe],
  templateUrl: './upload-panel.html',
})
export class UploadPanel {
  private readonly api = inject(DocumentApiService);
  private readonly fileInput = viewChild.required<ElementRef<HTMLInputElement>>('fileInput');

  readonly uploaded = output<ResearchDocument>();

  protected readonly reportTypes = REPORT_TYPES.map((value) => ({ value, label: REPORT_TYPE_LABELS[value] }));
  protected readonly maxUploadBytes = MAX_UPLOAD_BYTES;
  protected readonly file = signal<File | null>(null);
  protected readonly progress = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly dragging = signal(false);

  protected readonly form = new FormGroup({
    brokerageCompany: new FormControl('', { nonNullable: true, validators: [Validators.maxLength(200)] }),
    reportType: new FormControl<ReportType | ''>('', { nonNullable: true }),
    ticker: new FormControl('', {
      nonNullable: true,
      validators: [Validators.pattern(/^\s*[A-Za-z0-9.\-]{1,15}\s*$/)],
    }),
    publishedOn: new FormControl('', { nonNullable: true }),
  });

  protected onFileSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    this.selectFile(input.files?.[0] ?? null);
  }

  protected onDrop(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(false);
    this.selectFile(event.dataTransfer?.files?.[0] ?? null);
  }

  protected onDragOver(event: DragEvent): void {
    event.preventDefault();
    this.dragging.set(true);
  }

  protected submit(): void {
    const file = this.file();
    if (!file || this.form.invalid || this.progress() !== null) {
      this.form.markAllAsTouched();
      return;
    }
    const { brokerageCompany, reportType, ticker, publishedOn } = this.form.getRawValue();
    this.error.set(null);
    this.progress.set(0);

    this.api
      .upload(file, {
        brokerageCompany,
        reportType: reportType || undefined,
        ticker,
        publishedOn,
      })
      .subscribe({
        next: (event) => {
          if (event.type === HttpEventType.UploadProgress && event.total) {
            this.progress.set(Math.round((100 * event.loaded) / event.total));
          } else if (event.type === HttpEventType.Response && event.body) {
            this.progress.set(null);
            this.reset();
            this.uploaded.emit(event.body);
          }
        },
        error: (err) => {
          this.progress.set(null);
          this.error.set(errorMessage(err, 'Upload failed.'));
        },
      });
  }

  protected clearFile(): void {
    this.file.set(null);
    this.fileInput().nativeElement.value = '';
  }

  private selectFile(file: File | null): void {
    this.error.set(null);
    if (!file) {
      return;
    }
    const looksLikePdf = file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');
    if (!looksLikePdf) {
      this.error.set('Please choose a PDF file.');
      return;
    }
    if (file.size > MAX_UPLOAD_BYTES) {
      this.error.set('The file is larger than the 50 MB upload limit.');
      return;
    }
    this.file.set(file);
  }

  private reset(): void {
    this.form.reset();
    this.clearFile();
  }
}
