package com.investary.research.link;

/**
 * Processing state of an extracted link.
 * <ul>
 *   <li>{@code DISCOVERED} – found, not obviously a downloadable report</li>
 *   <li>{@code READY} – looks like a research PDF and can be downloaded</li>
 *   <li>{@code DOWNLOADING} – a download job is queued or running</li>
 *   <li>{@code DOWNLOADED} – stored in the document library ({@code documentId} is set)</li>
 *   <li>{@code FAILED} – the last download attempt failed; can be retried</li>
 *   <li>{@code SKIPPED} – navigation or promotion; not downloaded by default</li>
 * </ul>
 */
public enum LinkStatus {
    DISCOVERED,
    READY,
    DOWNLOADING,
    DOWNLOADED,
    FAILED,
    SKIPPED
}
