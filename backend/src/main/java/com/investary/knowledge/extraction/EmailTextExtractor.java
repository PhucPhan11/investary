package com.investary.knowledge.extraction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.investary.email.application.EmailViews.EmailContent;
import com.investary.email.domain.ParsingStatus;
import com.investary.email.parsing.AnalyzedContent;
import com.investary.email.parsing.ContentAnalyzer;
import com.investary.email.parsing.ContentBlock;
import com.investary.knowledge.KnowledgeProperties;
import com.investary.research.link.Keywords;

/**
 * Turns an imported email into indexable segments. It re-uses the content stored in Phase 2 (the
 * original HTML, or the normalized text for plain-text emails) and the Phase 2 block analysis, so the
 * EML file is never parsed again and section headings are available for citations.
 * <p>
 * Boilerplate (unsubscribe, copyright, "view in browser") and promotional sections are left out when
 * they are recognisable. Everything else is kept, including content whose research classification is
 * uncertain.
 */
@Component
public class EmailTextExtractor {

    private static final List<String> BOILERPLATE = List.of(
            "unsubscribe", "huy dang ky", "huy nhan", "tu choi nhan", "ngung nhan",
            "ban nhan duoc email nay", "you are receiving this", "you received this email",
            "xem email tren trinh duyet", "view in browser", "view this email", "view online",
            "privacy policy", "chinh sach bao mat", "all rights reserved", "ban quyen thuoc ve", "copyright");
    private static final List<String> PROMOTION_SECTIONS = List.of("uu dai", "khuyen mai", "promotion", "offers", "offer");
    private static final int MAX_BOILERPLATE_BLOCK_LENGTH = 400;
    private static final int MIN_TRANSLATION_BLOCKS = 3;
    private static final int MIN_PRIMARY_BLOCK_LENGTH = 40;

    private final ContentAnalyzer analyzer;
    private final KnowledgeProperties.BilingualMode bilingualMode;

    public EmailTextExtractor(ContentAnalyzer analyzer, KnowledgeProperties properties) {
        this.analyzer = analyzer;
        this.bilingualMode = properties.email().bilingualMode();
    }

    public ExtractedText extract(EmailContent email) {
        if (email.parsingStatus() == ParsingStatus.FAILED) {
            return ExtractedText.skipped(ExtractedText.SkipReason.PARSING_FAILED, null);
        }
        AnalyzedContent content;
        if (StringUtils.hasText(email.htmlContent())) {
            content = analyzer.analyzeHtml(email.htmlContent());
        } else if (StringUtils.hasText(email.normalizedText())) {
            content = analyzer.analyzePlainText(email.normalizedText());
        } else {
            return ExtractedText.skipped(ExtractedText.SkipReason.NO_TEXT, null);
        }

        Set<Integer> translated = bilingualMode == KnowledgeProperties.BilingualMode.PRIMARY_ONLY
                ? translatedCopy(content.blocks())
                : Set.of();

        List<TextSegment> segments = new ArrayList<>();
        int skipped = 0;
        for (ContentBlock block : content.blocks()) {
            if (block.isHeading()) {
                continue; // headings are carried as the section of the blocks below them
            }
            if (isBoilerplate(block) || isPromotion(block.section()) || translated.contains(block.index())) {
                skipped++;
                continue;
            }
            segments.add(new TextSegment(block.text(), null, block.section(), block.language()));
        }
        return ExtractedText.of(segments, null, skipped);
    }

    /**
     * Blocks that form a translated copy: a run of at least {@value #MIN_TRANSLATION_BLOCKS} consecutive
     * body blocks in another language than the first substantial block (newsletters put the original
     * first and the translation after it). A single foreign-language paragraph is never dropped, so
     * content cannot disappear just because one block is written in another language.
     */
    static Set<Integer> translatedCopy(List<ContentBlock> blocks) {
        String primary = null;
        Set<Integer> translated = new HashSet<>();
        List<Integer> run = new ArrayList<>();
        for (ContentBlock block : blocks) {
            if (block.isHeading() || block.language() == null) {
                continue;
            }
            if (primary == null) {
                if (block.text().length() < MIN_PRIMARY_BLOCK_LENGTH) {
                    continue; // e.g. an "English below" link before the original text
                }
                primary = block.language();
            }
            if (block.language().equals(primary)) {
                if (run.size() >= MIN_TRANSLATION_BLOCKS) {
                    translated.addAll(run);
                }
                run.clear();
            } else {
                run.add(block.index());
            }
        }
        if (run.size() >= MIN_TRANSLATION_BLOCKS) {
            translated.addAll(run);
        }
        return translated;
    }

    private static boolean isBoilerplate(ContentBlock block) {
        String text = block.text();
        if (text.length() < 2) {
            return true;
        }
        return text.length() <= MAX_BOILERPLATE_BLOCK_LENGTH && Keywords.containsAny(Keywords.normalize(text), BOILERPLATE);
    }

    private static boolean isPromotion(String section) {
        if (section == null) {
            return false;
        }
        String heading = Keywords.normalize(section.replaceFirst("^(\\d{1,2}|[IVX]{1,4})[.)]\\s*", ""));
        return Keywords.containsAny(heading, PROMOTION_SECTIONS);
    }
}
