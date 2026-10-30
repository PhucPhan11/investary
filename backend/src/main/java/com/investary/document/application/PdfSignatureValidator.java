package com.investary.document.application;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Detects PDF content from its magic bytes instead of trusting the file name or declared MIME type.
 * ISO 32000 tolerates a few bytes before the {@code %PDF-} header, so the first kilobyte is searched.
 */
public final class PdfSignatureValidator {

    private static final byte[] SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final int SEARCH_WINDOW = 1024;

    private PdfSignatureValidator() {
    }

    public static boolean isPdf(InputStream content) throws IOException {
        byte[] head = content.readNBytes(SEARCH_WINDOW);
        for (int i = 0; i <= head.length - SIGNATURE.length; i++) {
            if (matchesAt(head, i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAt(byte[] head, int offset) {
        for (int j = 0; j < SIGNATURE.length; j++) {
            if (head[offset + j] != SIGNATURE[j]) {
                return false;
            }
        }
        return true;
    }
}
