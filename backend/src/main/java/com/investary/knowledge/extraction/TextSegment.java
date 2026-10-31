package com.investary.knowledge.extraction;

/**
 * Smallest unit of extracted text that is never split across chunks unless it is too large on its
 * own: a PDF line or an email block.
 *
 * @param page     1-based PDF page number, {@code null} for emails
 * @param section  email heading path such as {@code "3. Khuyến nghị cổ phiếu"}, {@code null} if unknown
 * @param language {@code "vi"}, {@code "en"} or {@code null}
 */
public record TextSegment(String text, Integer page, String section, String language) {
}
