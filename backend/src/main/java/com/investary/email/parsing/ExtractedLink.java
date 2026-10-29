package com.investary.email.parsing;

/**
 * A hyperlink found in an email, deduplicated by target URL.
 *
 * @param originalUrl exactly as it appears in the email (may be a click-tracking URL)
 * @param targetUrl   the unwrapped destination, or {@code originalUrl} when it could not be unwrapped
 * @param section     heading path of the block that contains the first occurrence
 */
public record ExtractedLink(
        int position,
        String originalUrl,
        String targetUrl,
        String host,
        String displayText,
        String section,
        String language) {
}
