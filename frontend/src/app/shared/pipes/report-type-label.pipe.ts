import { Pipe, PipeTransform } from '@angular/core';

import { ReportType } from '../../core/models/document.model';

export const REPORT_TYPE_LABELS: Record<ReportType, string> = {
  MARKET_OVERVIEW: $localize`:@@reportType.MARKET_OVERVIEW:Market overview`,
  MACROECONOMIC: $localize`:@@reportType.MACROECONOMIC:Macroeconomic`,
  INDUSTRY_ANALYSIS: $localize`:@@reportType.INDUSTRY_ANALYSIS:Industry analysis`,
  STOCK_ANALYSIS: $localize`:@@reportType.STOCK_ANALYSIS:Stock analysis`,
  ETF_RESEARCH: $localize`:@@reportType.ETF_RESEARCH:ETF research`,
  OTHER: $localize`:@@reportType.OTHER:Other`,
};

@Pipe({ name: 'reportTypeLabel' })
export class ReportTypeLabelPipe implements PipeTransform {
  transform(type: ReportType | null | undefined): string {
    return type ? REPORT_TYPE_LABELS[type] : '—';
  }
}
