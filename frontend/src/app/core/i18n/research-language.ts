import { ResearchItem } from '../models/email.model';
import { Language } from './language';

/** Translation of an item that the source itself published (never produced by Investary or a model). */
export interface SourceTranslation {
  content: string;
  sourceRecommendation: string | null;
}

/** A research item as shown in the UI language. */
export interface LocalizedResearchItem {
  item: ResearchItem;
  /** Set when the item's language differs from the UI language and the source provides that language. */
  translation: SourceTranslation | null;
}

/** The source-provided translation of an item into `language`, if the item carries one. */
export function sourceTranslation(item: ResearchItem, language: Language): SourceTranslation | null {
  if (item.language === language) {
    return null;
  }
  const translations = item.structuredData?.['translations'];
  if (!translations || typeof translations !== 'object') {
    return null;
  }
  const entry = (translations as Record<string, unknown>)[language];
  if (!entry || typeof entry !== 'object') {
    return null;
  }
  const { content, sourceRecommendation } = entry as { content?: unknown; sourceRecommendation?: unknown };
  if (typeof content !== 'string' || !content.trim()) {
    return null;
  }
  return { content, sourceRecommendation: typeof sourceRecommendation === 'string' ? sourceRecommendation : null };
}

/**
 * Picks the items to show for a UI language. Bilingual emails (for example TCBS) contain the same research in
 * several languages; when at least one item is in the UI language, items in other languages are hidden unless the
 * source provides a translation into the UI language. Items without a language are always kept.
 * With `showAll`, or when nothing is in the UI language, every item is returned.
 */
export function localizeResearchItems(items: ResearchItem[], language: Language, showAll = false): LocalizedResearchItem[] {
  const hasLanguage = items.some((item) => item.language === language);
  return items
    .map((item) => ({ item, translation: sourceTranslation(item, language) }))
    .filter(
      ({ item, translation }) =>
        showAll || !hasLanguage || item.language === language || item.language === null || translation !== null,
    );
}

/** Number of items that `localizeResearchItems` hides for this language. */
export function hiddenResearchItemCount(items: ResearchItem[], language: Language): number {
  return items.length - localizeResearchItems(items, language).length;
}
