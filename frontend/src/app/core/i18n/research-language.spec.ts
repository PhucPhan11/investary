import { ResearchItem } from '../models/email.model';
import { hiddenResearchItemCount, localizeResearchItems, sourceTranslation } from './research-language';

function item(id: string, language: string | null, structuredData: Record<string, unknown> | null = null): ResearchItem {
  return {
    id,
    itemType: 'MARKET_UPDATE',
    ticker: null,
    brokerage: 'TCBS',
    publishedAt: null,
    title: id,
    content: `content ${id}`,
    structuredData,
    extractionMethod: 'RULES_V1',
    sourceLocation: 'block 1',
    language,
  };
}

const recommendation = item('rec-vi', 'vi', {
  sourceRecommendation: 'Chờ mua',
  translations: { en: { content: 'Awaiting Buy text', sourceRecommendation: 'Awaiting Buy' } },
});

describe('research-language', () => {
  const items = [item('a-vi', 'vi'), item('a-en', 'en'), item('none', null), recommendation];

  it('shows items in the UI language plus untagged items', () => {
    const ids = localizeResearchItems(items, 'vi').map((i) => i.item.id);
    expect(ids).toEqual(['a-vi', 'none', 'rec-vi']);
  });

  it('keeps items in another language when the source provides a translation', () => {
    const shown = localizeResearchItems(items, 'en');
    expect(shown.map((i) => i.item.id)).toEqual(['a-en', 'none', 'rec-vi']);
    expect(shown[2].translation).toEqual({ content: 'Awaiting Buy text', sourceRecommendation: 'Awaiting Buy' });
    expect(shown[0].translation).toBeNull();
    expect(hiddenResearchItemCount(items, 'en')).toBe(1);
  });

  it('shows every item when nothing is in the UI language or showAll is set', () => {
    const viOnly = [item('a-vi', 'vi'), item('b-vi', 'vi')];
    expect(localizeResearchItems(viOnly, 'en')).toHaveLength(2);
    expect(localizeResearchItems(items, 'vi', true)).toHaveLength(4);
  });

  it('ignores missing or malformed translations', () => {
    expect(sourceTranslation(item('x', 'vi', { translations: { en: { content: '' } } }), 'en')).toBeNull();
    expect(sourceTranslation(item('x', 'vi', { translations: 'en' }), 'en')).toBeNull();
    expect(sourceTranslation(recommendation, 'vi')).toBeNull();
  });
});
