/**
 * UI language support. The app is built once per language with @angular/localize (see ADR 0014), so the
 * language is fixed for the lifetime of a page and switching means loading the other build.
 */
export type Language = 'vi' | 'en';

/**
 * Set per build configuration in `angular.json` (`define`): the origin of the other language's dev server.
 * Undefined in the full production build, where both languages are served side by side under `/vi/` and `/en/`.
 */
declare const INVESTARY_ALT_LANGUAGE_URL: string | undefined;

/** Normalises an Angular `LOCALE_ID` (`vi`, `en`, `en-US`, …) to a supported UI language. */
export function toLanguage(localeId: string): Language {
  return localeId.toLowerCase().startsWith('vi') ? 'vi' : 'en';
}

export function otherLanguage(language: Language): Language {
  return language === 'vi' ? 'en' : 'vi';
}

/**
 * URL of the same page in the other language.
 *
 * @param routerUrl the current router URL (path, query and fragment, without the base href)
 * @param altOrigin the other build's origin in development; when absent, the production layout `/<lang>/` is used
 */
export function alternateLanguageUrl(
  language: Language,
  routerUrl: string,
  altOrigin: string | undefined = configuredAlternateOrigin(),
): string {
  const path = routerUrl.startsWith('/') ? routerUrl : `/${routerUrl}`;
  if (altOrigin) {
    return `${altOrigin.replace(/\/+$/, '')}${path}`;
  }
  return `/${otherLanguage(language)}${path}`;
}

function configuredAlternateOrigin(): string | undefined {
  return typeof INVESTARY_ALT_LANGUAGE_URL === 'string' ? INVESTARY_ALT_LANGUAGE_URL : undefined;
}
