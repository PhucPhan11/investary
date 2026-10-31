import { ReportType } from './document.model';

export type ParsingStatus = 'PARSED' | 'PARTIAL' | 'FAILED';

export type LinkCategory =
  | 'RESEARCH_REPORT'
  | 'STOCK_RESEARCH'
  | 'MARKET_REPORT'
  | 'NAVIGATION'
  | 'PROMOTION'
  | 'OTHER';

export type LinkStatus = 'DISCOVERED' | 'READY' | 'DOWNLOADING' | 'DOWNLOADED' | 'FAILED' | 'SKIPPED';

export type ResearchItemType =
  | 'MARKET_UPDATE'
  | 'MACRO_UPDATE'
  | 'INDUSTRY_UPDATE'
  | 'COMPANY_NEWS'
  | 'STOCK_RECOMMENDATION';

export interface LinkStatistics {
  totalLinks: number;
  researchLinks: number;
  documents: number;
  downloading: number;
  failed: number;
}

export interface EmailSummary {
  id: string;
  subject: string | null;
  senderName: string | null;
  senderAddress: string | null;
  sentAt: string | null;
  importedAt: string;
  brokerage: string | null;
  parsingStatus: ParsingStatus;
  statistics: LinkStatistics;
}

export interface EmailAttachment {
  id: string;
  position: number;
  filename: string | null;
  contentType: string;
  size: number;
  disposition: 'ATTACHMENT' | 'INLINE';
  documentId: string | null;
  importError: string | null;
}

export interface EmailDetail extends EmailSummary {
  recipients: string[];
  messageId: string | null;
  languages: string[];
  originalFilename: string;
  fileSize: number;
  contentSha256: string;
  parsingError: string | null;
  hasHtml: boolean;
  normalizedText: string | null;
  attachments: EmailAttachment[];
  sameMessageIdAs: string[];
}

export interface SourceLink {
  id: string;
  position: number;
  displayText: string | null;
  originalUrl: string;
  targetUrl: string;
  host: string;
  section: string | null;
  language: string | null;
  category: LinkCategory;
  classificationRule: string;
  tickerHint: string | null;
  status: LinkStatus;
  discoveredAt: string;
  lastAttemptAt: string | null;
  errorMessage: string | null;
  documentId: string | null;
  download: {
    status: 'PENDING' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
    attempts: number;
    maxAttempts: number;
    nextAttemptAt: string;
    lastError: string | null;
    updatedAt: string;
  } | null;
}

export interface LinkedDocument {
  documentId: string;
  originalFilename: string;
  fileSize: number;
  brokerageCompany: string | null;
  reportType: ReportType | null;
  ticker: string | null;
  publishedOn: string | null;
  uploadedAt: string;
  relationshipType: 'ATTACHMENT' | 'LINKED_REPORT';
  sourceLinkId: string | null;
  sourceAttachmentId: string | null;
  linkedAt: string;
}

export interface ResearchItem {
  id: string;
  itemType: ResearchItemType;
  ticker: string | null;
  brokerage: string | null;
  publishedAt: string | null;
  title: string | null;
  content: string;
  structuredData: Record<string, unknown> | null;
  extractionMethod: string;
  sourceLocation: string;
  language: string | null;
}

export interface Sender {
  address: string;
  name: string | null;
  messages: number;
}

export interface EmailQuery {
  q?: string;
  sender?: string;
  documentId?: string;
  page?: number;
  size?: number;
}
