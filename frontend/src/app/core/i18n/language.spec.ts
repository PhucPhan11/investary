import { alternateLanguageUrl, otherLanguage, toLanguage } from './language';

describe('language', () => {
  it('normalises locale ids', () => {
    expect(toLanguage('vi')).toBe('vi');
    expect(toLanguage('vi-VN')).toBe('vi');
    expect(toLanguage('en-US')).toBe('en');
    expect(toLanguage('en')).toBe('en');
    expect(otherLanguage('vi')).toBe('en');
  });

  it('points to the other dev server and keeps path, query and fragment', () => {
    expect(alternateLanguageUrl('vi', '/emails/1?tab=links#x', 'http://localhost:4201/')).toBe(
      'http://localhost:4201/emails/1?tab=links#x',
    );
  });

  it('uses the /<lang>/ layout of the production build without a configured origin', () => {
    expect(alternateLanguageUrl('en', '/journal', undefined)).toBe('/vi/journal');
    expect(alternateLanguageUrl('vi', 'portfolios', undefined)).toBe('/en/portfolios');
  });
});
