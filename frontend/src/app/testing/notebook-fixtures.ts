import { Note, NotebookDetail, NotebookMessage } from '../core/models/notebook.model';

export const NOTEBOOK: NotebookDetail = {
  id: 'nb1',
  title: 'SCS 2026',
  description: 'Cảng hàng không',
  version: 0,
  createdAt: '2026-11-20T01:00:00Z',
  updatedAt: '2026-11-21T01:00:00Z',
  sources: [
    {
      sourceType: 'DOCUMENT',
      sourceId: 'd1',
      title: 'TCBS SCS cập nhật',
      brokerage: 'TCBS',
      publishedOn: '2026-10-20',
      dateBasis: 'PUBLISHED',
      sentAt: null,
      importedAt: '2026-10-21T01:00:00Z',
      tickers: ['SCS'],
      indexState: 'INDEXED',
      addedAt: '2026-11-20T01:00:00Z',
    },
    {
      sourceType: 'EMAIL',
      sourceId: 'm1',
      title: 'Bản tin SSI',
      brokerage: 'SSI',
      publishedOn: null,
      dateBasis: null,
      sentAt: '2026-10-25T02:00:00Z',
      importedAt: '2026-10-25T03:00:00Z',
      tickers: [],
      indexState: 'NOT_TRACKED',
      addedAt: '2026-11-20T01:00:00Z',
    },
  ],
  tickers: ['SCS'],
  searchableSources: 1,
};

export const QUESTION: NotebookMessage = {
  id: 'q1',
  role: 'USER',
  content: 'TCBS khuyến nghị gì?',
  evidenceStatus: null,
  warnings: [],
  model: null,
  createdAt: '2026-11-21T01:00:00Z',
  citations: [],
};

export const ANSWER: NotebookMessage = {
  id: 'a1',
  role: 'ASSISTANT',
  content: 'Theo TCBS, khuyến nghị Mua [S1].',
  evidenceStatus: 'SUPPORTED',
  warnings: [],
  model: 'ollama:test',
  createdAt: '2026-11-21T01:00:01Z',
  citations: [
    {
      label: 'S1',
      sourceType: 'DOCUMENT',
      sourceId: 'd1',
      available: true,
      chunkId: 'c1',
      title: 'TCBS SCS cập nhật',
      publisher: 'TCBS',
      publishedOn: '2026-10-20',
      dateBasis: 'PUBLISHED',
      location: 'trang 1',
      excerpt: 'Khuyến nghị: Mua.',
    },
  ],
};

export const CONSENSUS_NOTE: Note = {
  id: 'n1',
  kind: 'CONSENSUS',
  ticker: 'SCS',
  status: 'DONE',
  content: {
    ticker: 'SCS',
    sources: [
      {
        group: 'G1',
        sourceType: 'EMAIL',
        sourceId: 'm1',
        title: 'Bản tin SSI',
        brokerage: 'SSI',
        date: '2026-10-25',
        dateBasis: 'EMAIL_SENT',
        rating: 'Bán',
        ratingLocation: '1. Khuyến nghị cổ phiếu',
        view: { text: 'SSI khuyến nghị Bán [S2].', evidenceStatus: 'SUPPORTED', labels: ['S2'] },
      },
    ],
    agreements: [],
    disagreements: [{ text: 'TCBS và SSI trái ngược [S1, S2].', evidenceStatus: 'SUPPORTED', labels: ['S1', 'S2'] }],
  },
  limitations: ['Đã bỏ một nhận định vì có dạng lệnh giao dịch.'],
  evidenceStatus: 'PARTIAL',
  model: 'ollama:test',
  errorSummary: null,
  createdAt: '2026-11-21T02:00:00Z',
  completedAt: '2026-11-21T02:00:30Z',
  citations: [
    { ...ANSWER.citations[0], label: 'S1' },
    { ...ANSWER.citations[0], label: 'S2', sourceType: 'EMAIL', sourceId: null, available: false, title: 'Bản tin SSI' },
  ],
  reused: false,
};
