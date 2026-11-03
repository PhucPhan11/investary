import { DatePipe } from '@angular/common';
import { Component, DestroyRef, HostListener, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { NotificationApiService, notificationLink } from '../../core/api/notification-api.service';
import { AppNotification } from '../../core/models/automation.model';

const POLL_MS = 60_000;

/**
 * Header bell: unread count (polled every minute and when the window regains focus) and a panel with the
 * latest notifications. Notifications are created only by the local backend.
 */
@Component({
  selector: 'app-notification-bell',
  imports: [DatePipe, RouterLink],
  template: `
    <div class="bell">
      <button
        type="button"
        class="bell__button"
        [attr.aria-expanded]="open()"
        aria-controls="notification-panel"
        [attr.aria-label]="unread() > 0 ? 'Notifications, ' + unread() + ' unread' : 'Notifications'"
        (click)="toggle()"
      >
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
          <path d="M6 8a6 6 0 1 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
          <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
        </svg>
        @if (unread() > 0) {
          <span class="bell__count" data-testid="unread-count">{{ unread() > 99 ? '99+' : unread() }}</span>
        }
      </button>
      @if (open()) {
        <div id="notification-panel" class="bell__panel" role="dialog" aria-label="Notifications">
          <div class="bell__head">
            <strong>Notifications</strong>
            <button type="button" class="link-button small" [disabled]="unread() === 0" (click)="readAll()">Mark all read</button>
          </div>
          @if (error()) {
            <p class="small link-error">{{ error() }}</p>
          } @else if (items().length === 0) {
            <p class="small muted">No notifications yet.</p>
          } @else {
            <ul class="notification-items">
              @for (n of items(); track n.id) {
                <li class="notification-item" [class.notification-item--unread]="!n.read" [attr.data-priority]="n.priority">
                  @if (link(n); as target) {
                    <a class="notification-item__title" [routerLink]="target" (click)="openItem(n)">{{ n.title }}</a>
                  } @else {
                    <span class="notification-item__title">{{ n.title }}</span>
                  }
                  <span class="small muted">{{ n.message }}</span>
                  <span class="notification-item__meta small muted">
                    {{ n.createdAt | date: 'short' }} ·
                    <button type="button" class="link-button small" (click)="setRead(n, !n.read)">
                      {{ n.read ? 'Mark unread' : 'Mark read' }}
                    </button>
                  </span>
                </li>
              }
            </ul>
          }
          <a class="link small" routerLink="/notifications" (click)="open.set(false)">View all notifications</a>
        </div>
      }
    </div>
  `,
})
export class NotificationBell {
  private readonly api = inject(NotificationApiService);
  private timer: ReturnType<typeof setInterval> | null = null;

  protected readonly unread = signal(0);
  protected readonly open = signal(false);
  protected readonly items = signal<AppNotification[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly link = notificationLink;

  constructor() {
    this.refreshCount();
    this.timer = setInterval(() => this.refreshCount(), POLL_MS);
    inject(DestroyRef).onDestroy(() => {
      if (this.timer) {
        clearInterval(this.timer);
      }
    });
  }

  @HostListener('window:focus')
  protected refreshCount(): void {
    this.api.unreadCount().subscribe({
      next: (result) => this.unread.set(result.count),
      error: () => this.unread.set(0),
    });
  }

  @HostListener('document:keydown.escape')
  protected close(): void {
    this.open.set(false);
  }

  protected toggle(): void {
    this.open.set(!this.open());
    if (this.open()) {
      this.loadItems();
    }
  }

  protected setRead(n: AppNotification, read: boolean): void {
    this.api.setRead(n.id, read).subscribe({
      next: (updated) => {
        this.items.update((list) => list.map((item) => (item.id === updated.id ? updated : item)));
        this.refreshCount();
      },
    });
  }

  protected openItem(n: AppNotification): void {
    this.open.set(false);
    if (!n.read) {
      this.setRead(n, true);
    }
  }

  protected readAll(): void {
    this.api.readAll().subscribe({
      next: () => {
        this.items.update((list) => list.map((item) => ({ ...item, read: true })));
        this.unread.set(0);
      },
    });
  }

  private loadItems(): void {
    this.api.list(false, '', 0, 10).subscribe({
      next: (page) => {
        this.items.set(page.content);
        this.error.set(null);
      },
      error: () => this.error.set('Notifications could not be loaded.'),
    });
  }
}
