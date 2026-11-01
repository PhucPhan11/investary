import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { NotificationApiService, notificationLink } from '../../core/api/notification-api.service';
import { errorMessage } from '../../core/api/error-message';
import { AppNotification, NotificationType } from '../../core/models/automation.model';
import { Alert } from '../../shared/components/alert';
import { EmptyState } from '../../shared/components/empty-state';
import { Pager } from '../../shared/components/pager';
import { StatusBadge } from '../../shared/components/status-badge';

export const NOTIFICATION_TYPE_LABELS: Record<NotificationType, string> = {
  NEW_RESEARCH: 'New research',
  PORTFOLIO_RESEARCH: 'Portfolio research',
  RESEARCH_RISK: 'Possible risk',
  RESEARCH_CONFLICT: 'Conflicting views',
  DAILY_BRIEF_READY: 'Daily brief',
  SYNC_FAILED: 'Gmail sync',
  PROCESSING_FAILED: 'Processing failed',
};

/** All in-app notifications with read/unread state and links to what they refer to. */
@Component({
  selector: 'app-notification-list',
  imports: [DatePipe, FormsModule, RouterLink, Alert, EmptyState, Pager, StatusBadge],
  template: `
    <header class="page-header">
      <div>
        <h1 class="page-title">Notifications</h1>
        <p class="page-subtitle">
          Created by deterministic rules from stored research matches, validated assessments and job states. They are
          prompts to review, not trading signals.
        </p>
      </div>
    </header>

    @if (error(); as message) {
      <app-alert kind="error" [dismissible]="true" (dismissed)="error.set(null)">{{ message }}</app-alert>
    }

    <section class="card">
      <form class="filters" (ngSubmit)="load(0)">
        <label class="field">
          <span class="field__label">Type</span>
          <select class="input" name="type" [(ngModel)]="type">
            <option value="">All types</option>
            @for (t of types; track t) {
              <option [value]="t">{{ typeLabels[t] }}</option>
            }
          </select>
        </label>
        <label class="small checkbox-row"><input type="checkbox" name="unreadOnly" [(ngModel)]="unreadOnly" /> Unread only</label>
        <div class="actions">
          <button type="submit" class="btn btn--primary btn--small">Apply</button>
          <button type="button" class="btn btn--ghost btn--small" (click)="readAll()">Mark all read</button>
        </div>
      </form>

      @if (loading()) {
        <p class="muted" role="status">Loading notifications…</p>
      } @else if (items().length === 0) {
        <app-empty-state title="No notifications">Nothing needs your attention.</app-empty-state>
      } @else {
        <ul class="notification-items notification-items--page">
          @for (n of items(); track n.id) {
            <li class="notification-item" [class.notification-item--unread]="!n.read" [attr.data-id]="n.id">
              <div class="notification-item__head">
                <app-status-badge [value]="'NOTIF_' + n.priority" />
                <span class="small muted">{{ typeLabels[n.type] }} · {{ n.createdAt | date: 'medium' }}</span>
              </div>
              @if (link(n); as target) {
                <a class="notification-item__title" [routerLink]="target" (click)="markRead(n)">{{ n.title }}</a>
              } @else {
                <span class="notification-item__title">{{ n.title }}</span>
              }
              <span class="small">{{ n.message }}</span>
              <span class="small">
                <button type="button" class="link-button" (click)="toggle(n)">{{ n.read ? 'Mark unread' : 'Mark read' }}</button>
              </span>
            </li>
          }
        </ul>
        @if (totalPages() > 1) {
          <app-pager [page]="page()" [totalPages]="totalPages()" (pageChange)="load($event)" />
        }
      }
    </section>
  `,
})
export class NotificationList implements OnInit {
  private readonly api = inject(NotificationApiService);

  protected readonly items = signal<AppNotification[]>([]);
  protected readonly page = signal(0);
  protected readonly totalPages = signal(0);
  protected readonly loading = signal(true);
  protected readonly error = signal<string | null>(null);
  protected readonly typeLabels = NOTIFICATION_TYPE_LABELS;
  protected readonly types = Object.keys(NOTIFICATION_TYPE_LABELS) as NotificationType[];
  protected readonly link = notificationLink;
  protected type: NotificationType | '' = '';
  protected unreadOnly = false;

  ngOnInit(): void {
    this.load(0);
  }

  protected load(page: number): void {
    this.loading.set(true);
    this.api.list(this.unreadOnly, this.type, page, 20).subscribe({
      next: (result) => {
        this.items.set(result.content);
        this.page.set(result.page);
        this.totalPages.set(result.totalPages);
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        this.error.set(errorMessage(err, 'Could not load the notifications.'));
      },
    });
  }

  protected toggle(n: AppNotification): void {
    this.api.setRead(n.id, !n.read).subscribe({
      next: (updated) => this.items.update((list) => list.map((item) => (item.id === updated.id ? updated : item))),
      error: (err) => this.error.set(errorMessage(err, 'Could not update the notification.')),
    });
  }

  protected markRead(n: AppNotification): void {
    if (!n.read) {
      this.api.setRead(n.id, true).subscribe();
    }
  }

  protected readAll(): void {
    this.api.readAll().subscribe({
      next: () => this.load(this.page()),
      error: (err) => this.error.set(errorMessage(err, 'Could not mark the notifications as read.')),
    });
  }
}
