export const REPORT_TYPES = [
  'MARKET_OVERVIEW',
  'MACROECONOMIC',
  'INDUSTRY_ANALYSIS',
  'STOCK_ANALYSIS',
  'ETF_RESEARCH',
  'OTHER',
] as const;

export type ReportType = (typeof REPORT_TYPES)[number];

export interface ResearchDocument {
  id: string;
  originalFilename: string;
  fileSize: number;
  mimeType: string;
  sha256Checksum: string;
  brokerageCompany: string | null;
  reportType: ReportType | null;
  ticker: string | null;
  publishedOn: string | null;
  uploadedAt: string;
}

export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface UploadMetadata {
  brokerageCompany?: string;
  reportType?: ReportType;
  ticker?: string;
  publishedOn?: string;
}

/** RFC 9457 problem details as returned by the backend. */
export interface ProblemDetail {
  title?: string;
  detail?: string;
  status?: number;
  code?: string;
  existingDocumentId?: string;
  existingEmailId?: string;
  errors?: Record<string, string>;
}
