package com.investary.knowledge.extraction;

import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import com.investary.knowledge.KnowledgeProperties;

/**
 * Extracts PDF text page by page with PDFBox, so every segment carries the real 1-based page number.
 * Each non-empty line becomes one segment; chunking never splits inside a line, which keeps table rows
 * and figures together with their labels.
 */
@Component
public class PdfTextExtractor {

    private final KnowledgeProperties.Pdf settings;

    public PdfTextExtractor(KnowledgeProperties properties) {
        this.settings = properties.pdf();
    }

    public ExtractedText extract(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                return ExtractedText.skipped(ExtractedText.SkipReason.NO_TEXT, 0);
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            List<TextSegment> segments = new ArrayList<>();
            int emptyPages = 0;
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = Normalizer.normalize(stripper.getText(document), Normalizer.Form.NFC);
                int visible = 0;
                for (String rawLine : text.split("\\R")) {
                    String line = cleanLine(rawLine);
                    if (!line.isEmpty()) {
                        segments.add(new TextSegment(line, page, null, null));
                        visible += line.length();
                    }
                }
                if (visible < settings.minCharsPerPage()) {
                    emptyPages++;
                }
            }
            if ((double) emptyPages / pageCount >= settings.maxEmptyPageRatio()) {
                return ExtractedText.skipped(ExtractedText.SkipReason.OCR_REQUIRED, pageCount);
            }
            return ExtractedText.of(segments, pageCount, 0);
        } catch (InvalidPasswordException e) {
            throw new ExtractionException("The PDF is password protected", e);
        } catch (IOException e) {
            throw new ExtractionException("The PDF could not be read: " + e.getMessage(), e);
        }
    }

    static String cleanLine(String line) {
        // Drop control and private-use characters that some PDF fonts produce, collapse spacing.
        return line.replaceAll("[\\p{Cc}\\p{Co}\\uFFFD&&[^\\t]]", "")
                .replace(' ', ' ')
                .replaceAll("[ \\t]+", " ")
                .strip();
    }
}
