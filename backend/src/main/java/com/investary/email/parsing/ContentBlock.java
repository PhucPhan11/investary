package com.investary.email.parsing;

/**
 * One visible block of email content (paragraph, list item, heading, table cell) in reading order.
 *
 * @param headingLevel 0 for body text, 1 for top-level ("1. ...") headings, 2 for sub-headings
 * @param section      heading path the block belongs to, e.g. {@code "2. Market news > General market"}
 * @param lead         bold text at the start of a body block (e.g. {@code "VN-Index:"}), or {@code null}
 * @param language     {@code "vi"}, {@code "en"} or {@code null}
 */
public record ContentBlock(int index, String text, int headingLevel, String section, String lead, String language) {

    public boolean isHeading() {
        return headingLevel > 0;
    }
}
