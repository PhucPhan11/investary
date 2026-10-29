package com.investary.knowledge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Builds synthetic research PDFs at test time (real PDFs are never committed). Text uses Liberation
 * Sans, which ships inside PDFBox and covers Vietnamese, embedded as a Unicode font.
 */
public final class TestResearchPdfs {

    private TestResearchPdfs() {
    }

    /**
     * @param pages lines of text per page; an empty list produces an empty page
     */
    public static byte[] textPdf(List<List<String>> pages) {
        try (PDDocument document = new PDDocument()) {
            PDType0Font font;
            try (InputStream in = PDDocument.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                font = PDType0Font.load(document, in);
            }
            for (List<String> lines : pages) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                if (lines.isEmpty()) {
                    continue;
                }
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 10);
                    content.setLeading(14);
                    content.newLineAtOffset(50, 790);
                    for (String line : lines) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A "scanned" PDF: pages that only contain an image, no text layer. */
    public static byte[] imageOnlyPdf(int pages) {
        try (PDDocument document = new PDDocument()) {
            java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(20, 20, java.awt.image.BufferedImage.TYPE_INT_RGB);
            PDImageXObject xObject = LosslessFactory.createFromImage(document, image);
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.drawImage(xObject, 50, 500, 400, 300);
                }
            }
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }
}
