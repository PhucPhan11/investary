package com.investary.document;

import java.nio.charset.StandardCharsets;

/**
 * Generates small PDF-signed byte arrays so no binary fixtures need to be committed.
 */
public final class TestPdfs {

    private TestPdfs() {
    }

    public static byte[] pdf(String marker) {
        String body = "%PDF-1.4\n% " + marker + "\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n";
        return body.getBytes(StandardCharsets.US_ASCII);
    }

    public static byte[] notPdf() {
        return "PK\u0003\u0004 this is a zip archive pretending to be a pdf".getBytes(StandardCharsets.ISO_8859_1);
    }
}
