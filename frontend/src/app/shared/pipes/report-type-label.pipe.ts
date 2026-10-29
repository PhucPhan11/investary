import { Pipe, PipeTransform } from '@angular/core';

import { ReportType } from '../../core/models/document.model';

export const REPORT_TYPE_LABELS: Record<ReportType, string> = {
  MARKET_OVERVIEW: 'Market overview',
  MACROECONOMIC: 'Macroeconomic',
  INDUSTRY_ANALYSIS: 'Industry analysis',
  STOCK_ANALYSIS: 'Stock analysis',
  ETF_RESEARCH: 'ETF research',
  OTHER: 'Other',
};

@Pipe({ name: 'reportTypeLabel' })
export class ReportTypeLabelPipe implements PipeTransform {
  transform(type: ReportType | null | undefined): string {
    return type ? REPORT_TYPE_LABELS[type] : '—';
  }
}
