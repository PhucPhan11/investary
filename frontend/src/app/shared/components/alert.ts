import { Component, input, output } from '@angular/core';

@Component({
  selector: 'app-alert',
  template: `
    <div [class]="'alert alert--' + kind()" [attr.role]="kind() === 'error' ? 'alert' : 'status'">
      <span class="alert__text"><ng-content /></span>
      @if (dismissible()) {
        <button type="button" class="alert__close" aria-label="Dismiss" (click)="dismissed.emit()">×</button>
      }
    </div>
  `,
})
export class Alert {
  readonly kind = input<'success' | 'error' | 'info'>('info');
  readonly dismissible = input(false);
  readonly dismissed = output<void>();
}
