package com.investary.intelligence.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.investary.intelligence.application.BriefViews.BriefItem;
import com.investary.intelligence.domain.BriefItemKind;
import com.investary.knowledge.assistant.CitationValidator;
import com.investary.knowledge.assistant.EvidenceStatus;
import com.investary.research.link.Keywords;

import tools.jackson.databind.JsonNode;

/**
 * Checks the model part of a brief against the evidence it was given. Every text is validated by
 * {@link CitationValidator} against only the labels it may cite (a holding's own evidence, the market
 * evidence, or all evidence for the summary and risks); labels outside that set are removed and lower the
 * status. Items without a valid citation, about symbols outside the plan, or containing trading
 * instructions are dropped with a warning. The deterministic part of the brief is never changed here.
 */
final class BriefValidator {

    /** Trading instructions (accent-folded); a brokerage rating quoted from a source is not one of these. */
    private static final Pattern TRADING = Pattern.compile(
            "\\b(mua vao|mua them|ban ra|ban bot|ban het|chot loi|cat lo|dat lenh|giai ngan|tang ty trong|giam ty trong|"
                    + "nen mua|nen ban|buy now|sell now|take profit|stop loss|place an order)\\b");
    private static final int MAX_ITEMS = 5;

    private BriefValidator() {
    }

    /**
     * @param holdings      held symbol (upper case) → labels it may cite
     * @param opportunities not-held ticker (upper case) → labels it may cite
     * @param marketLabels  labels of market-wide sources
     * @param sources       label → verbatim source text that was sent
     * @param facts         backend facts (portfolio, period, counts) whose figures may be repeated without citation
     */
    static Review review(JsonNode node, Map<String, List<String>> holdings, Map<String, List<String>> opportunities,
                         List<String> marketLabels, Map<String, String> sources, String facts) {
        List<String> warnings = new ArrayList<>();
        List<String> all = List.copyOf(sources.keySet());

        BriefItem summary = text(node.path("executiveSummary").asString(""), all, sources, facts, "Tóm tắt", warnings);
        BriefItem market = null;
        String marketText = node.path("marketOverview").asString("").strip();
        if (!marketText.isEmpty()) {
            if (marketLabels.isEmpty()) {
                warnings.add("Đã bỏ phần tổng quan thị trường của mô hình vì không có nghiên cứu thị trường mới.");
            } else {
                market = text(marketText, marketLabels, sources, facts, "Thị trường", warnings);
            }
        }

        Map<String, List<BriefItem>> findings = new LinkedHashMap<>();
        symbolItems(node.path("portfolioFindings"), holdings, sources, facts, warnings, findings, "danh mục");
        Map<String, List<BriefItem>> ideas = new LinkedHashMap<>();
        symbolItems(node.path("opportunities"), opportunities, sources, facts, warnings, ideas, "cơ hội nghiên cứu");

        List<BriefItem> risks = new ArrayList<>();
        for (JsonNode risk : node.path("risks")) {
            if (risks.size() >= MAX_ITEMS) {
                break;
            }
            BriefItem item = text(risk.isString() ? risk.asString("") : risk.path("finding").asString(""), all, sources, facts, "Rủi ro",
                    warnings);
            if (item != null) {
                risks.add(item);
            }
        }

        List<BriefItem> followUps = new ArrayList<>();
        for (JsonNode followUp : node.path("followUps")) {
            String value = followUp.asString("").strip();
            if (value.isEmpty() || followUps.size() >= MAX_ITEMS) {
                continue;
            }
            if (isTradingInstruction(value)) {
                warnings.add("Đã bỏ một gợi ý của mô hình vì có dạng lệnh giao dịch.");
                continue;
            }
            followUps.add(new BriefItem(BriefItemKind.AI_INTERPRETATION, stripMarkers(value), null, List.of(), null, null, null, null, null,
                    null));
        }
        return new Review(summary, market, findings, ideas, risks, followUps, List.copyOf(warnings));
    }

    private static void symbolItems(JsonNode array, Map<String, List<String>> allowed, Map<String, String> sources, String facts,
                                    List<String> warnings, Map<String, List<BriefItem>> result, String area) {
        for (JsonNode element : array) {
            String symbol = element.path("symbol").asString("").strip().toUpperCase(Locale.ROOT);
            List<String> labels = allowed.get(symbol);
            if (labels == null) {
                warnings.add("Đã bỏ nhận định về mã " + (symbol.isEmpty() ? "(trống)" : symbol) + " vì không thuộc phần " + area
                        + " có nguồn mới.");
                continue;
            }
            List<BriefItem> items = result.computeIfAbsent(symbol, s -> new ArrayList<>());
            if (items.size() >= 2) {
                continue;
            }
            BriefItem item = text(element.path("finding").asString(""), labels, sources, facts, symbol, warnings);
            if (item != null) {
                items.add(new BriefItem(item.kind(), item.text(), item.evidenceStatus(), item.citations(), symbol, null, null, null, null,
                        null));
            }
        }
        result.values().removeIf(List::isEmpty);
    }

    /**
     * Validates one model text against the labels it may cite.
     *
     * @return the validated item, or null when it was dropped
     */
    static BriefItem text(String raw, List<String> labels, Map<String, String> sources, String facts, String context, List<String> warnings) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        if (isTradingInstruction(value)) {
            warnings.add(context + ": đã bỏ nhận định của mô hình vì có dạng lệnh giao dịch.");
            return null;
        }
        Map<String, String> allowed = new LinkedHashMap<>();
        labels.forEach(label -> {
            if (sources.containsKey(label)) {
                allowed.put(label, sources.get(label));
            }
        });
        CitationValidator.Result validated = CitationValidator.validate(value, EvidenceStatus.SUPPORTED, allowed, facts);
        validated.warnings().forEach(w -> warnings.add(context + ": " + w));
        if (validated.citedLabels().isEmpty()) {
            warnings.add(context + ": đã bỏ nhận định của mô hình vì không có trích dẫn hợp lệ.");
            return null;
        }
        return new BriefItem(BriefItemKind.AI_INTERPRETATION, validated.answer(), validated.status(), validated.citedLabels(), null, null,
                null, null, null, null);
    }

    static boolean isTradingInstruction(String text) {
        return TRADING.matcher(Keywords.normalize(text)).find();
    }

    /** Follow-ups are uncited suggestions; a marker there would point at nothing that was checked. */
    private static String stripMarkers(String text) {
        return text.replaceAll("\\[\\s*S\\d+(?:\\s*[,;]\\s*S\\d+)*\\s*]", "").replaceAll("\\s{2,}", " ").strip();
    }

    /**
     * Validated model output. Maps are keyed by upper-case symbol.
     */
    record Review(BriefItem summary, BriefItem market, Map<String, List<BriefItem>> findings, Map<String, List<BriefItem>> opportunities,
                  List<BriefItem> risks, List<BriefItem> followUps, List<String> warnings) {
    }
}
