import { Component, input, output } from '@angular/core';

@Component({
  selector: 'app-pager',
  template: `
    @if (totalPages() > 1) {
      <nav class="pager" aria-label="Pagination" i18n-aria-label="@@pager.label">
        <button type="button" class="btn btn--small btn--ghost" [disabled]="page() === 0 || disabled()" (click)="pageChange.emit(page() - 1)" i18n="@@pager.previous">
          ← Previous
        </button>
        <span class="muted" i18n="@@pager.position">Page {{ page() + 1 }} of {{ totalPages() }}</span>
        <button
          type="button"
          class="btn btn--small btn--ghost"
          [disabled]="page() + 1 >= totalPages() || disabled()"
          (click)="pageChange.emit(page() + 1)"
          i18n="@@pager.next"
        >
          Next →
        </button>
      </nav>
    }
  `,
})
export class Pager {
  readonly page = input.required<number>();
  readonly totalPages = input.required<number>();
  readonly disabled = input(false);
  readonly pageChange = output<number>();
}
