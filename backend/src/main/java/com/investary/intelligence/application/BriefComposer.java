package com.investary.intelligence.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import com.investary.intelligence.application.BriefConflicts.View;
import com.investary.intelligence.application.BriefEvidenceCollector.AssessmentRef;
import com.investary.intelligence.application.BriefEvidenceCollector.BriefEvidence;
import com.investary.intelligence.application.BriefEvidenceCollector.Entry;
import com.investary.intelligence.application.BriefValidator.Review;
import com.investary.intelligence.application.BriefViews.BriefContent;
import com.investary.intelligence.application.BriefViews.BriefItem;
import com.investary.intelligence.application.BriefViews.BriefSection;
import com.investary.intelligence.application.BriefViews.Limitation;
import com.investary.intelligence.domain.BriefItemKind;
import com.investary.intelligence.domain.BriefSectionKey;
import com.investary.intelligence.domain.Relevance;
import com.investary.intelligence.domain.ResearchState;
import com.investary.knowledge.assistant.Citations;
import com.investary.knowledge.assistant.EvidenceStatus;
import com.investary.knowledge.assistant.PromptBuilder.LabeledHit;
import com.investary.knowledge.search.ResearchAvailabilityService.PendingSource;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.ValuationStatus;
import com.investary.research.link.Keywords;

/**
 * Writes the sections of a brief in Java: every count, date, relevance, quote and citation here comes from
 * stored records. Validated model text ({@link Review}) is added to the matching sections as
 * {@link BriefItemKind#AI_INTERPRETATION}; without it the brief is still complete, only shorter.
 */
final class BriefComposer {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy", Locale.ROOT);
    private static final int EXCERPT = 280;
    private static final int MAX_FOLLOW_UPS = 8;

    private BriefComposer() {
    }

    static String period(Instant start, Instant end, ZoneId zone) {
        return DATE_TIME.format(start.atZone(zone)) + " – " + DATE_TIME.format(end.atZone(zone)) + " (" + zone.getId() + ")";
    }

    /**
     * @param review      validated model output, or null when the model was not used or failed
     * @param limitations limitations decided by the caller (AI status, partial period); more are added here
     */
    static Composition compose(BriefEvidence evidence, Instant start, Instant end, ZoneId zone, Review review,
                               List<Limitation> limitations) {
        List<Limitation> notes = new ArrayList<>(limitations);
        Map<String, List<View>> conflicts = BriefConflicts.detect(evidence.entries());
        List<BriefSection> sections = new ArrayList<>();
        sections.add(summary(evidence, start, end, zone, review));
        sections.add(market(evidence, review));
        sections.add(portfolio(evidence, zone, review));
        sections.add(opportunities(evidence, review));
        sections.add(risks(conflicts, review));
        sections.add(followUps(evidence, conflicts, review));
        addSourceLimitations(evidence, notes, zone);
        sections.add(sources(evidence, notes));

        Set<String> cited = new LinkedHashSet<>();
        sections.forEach(section -> section.items().forEach(item -> cited.addAll(item.citations())));
        EvidenceStatus overall = sections.stream().map(BriefSection::evidenceStatus).filter(Objects::nonNull)
                .reduce(EvidenceStatus.SUPPORTED, EvidenceStatus::atMost);
        boolean anyChecked = sections.stream().anyMatch(s -> s.evidenceStatus() != null);
        String portfolioName = evidence.snapshot() == null ? null : evidence.snapshot().summary().name();
        return new Composition(new BriefContent(portfolioName, evidence.newSources(), evidence.pending().size(), List.copyOf(sections)),
                List.copyOf(notes), anyChecked ? overall : null, cited);
    }

    private static BriefSection summary(BriefEvidence evidence, Instant start, Instant end, ZoneId zone, Review review) {
        List<BriefItem> items = new ArrayList<>();
        String period = period(start, end, zone);
        int pending = evidence.pending().size();
        if (evidence.state() == ResearchState.NO_MATERIAL_RESEARCH) {
            items.add(BriefItem.fact("Kỳ " + period + ": không có nghiên cứu mới đáng kể."));
        } else if (evidence.state() == ResearchState.PENDING_ONLY) {
            items.add(BriefItem.fact("Kỳ " + period + ": chưa có nghiên cứu mới được xử lý xong; " + pending
                    + " nguồn đã nhận nhưng đang chờ xử lý hoặc xử lý lỗi."));
        } else {
            Set<String> brokerages = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            evidence.entries().stream().map(e -> e.source().brokerage()).filter(Objects::nonNull).forEach(brokerages::add);
            Set<String> touched = new LinkedHashSet<>();
            long related = evidence.entries().stream().filter(e -> !e.heldSymbols().isEmpty()).peek(e -> touched.addAll(e.heldSymbols())).count();
            StringBuilder text = new StringBuilder("Kỳ ").append(period).append(": ").append(evidence.newSources())
                    .append(" nguồn nghiên cứu mới");
            if (!brokerages.isEmpty()) {
                text.append(" từ ").append(String.join(", ", brokerages));
            }
            text.append('.');
            if (evidence.portfolioId() != null) {
                text.append(' ').append(related).append(" nguồn liên quan tới danh mục")
                        .append(touched.isEmpty() ? "." : " (" + String.join(", ", touched) + ").");
            }
            if (pending > 0) {
                text.append(' ').append(pending).append(" nguồn khác đã nhận nhưng chưa xử lý xong.");
            }
            items.add(BriefItem.fact(text.toString()));
        }
        if (review != null && review.summary() != null) {
            items.add(review.summary());
        }
        return section(BriefSectionKey.EXECUTIVE_SUMMARY, "Tóm tắt", null, items);
    }

    private static BriefSection market(BriefEvidence evidence, Review review) {
        List<BriefItem> items = new ArrayList<>();
        List<Entry> market = evidence.entries().stream().filter(e -> e.relevance() == Relevance.MARKET).toList();
        if (market.isEmpty()) {
            items.add(BriefItem.fact("Không có nghiên cứu thị trường mới trong kỳ."));
        } else {
            market.forEach(entry -> items.add(claim(entry, null, null)));
            if (review != null && review.market() != null) {
                items.add(review.market());
            }
        }
        return section(BriefSectionKey.MARKET_OVERVIEW, "Tổng quan thị trường",
                "Investary không có dữ liệu giá hay chỉ số thị trường; mục này chỉ tóm tắt nghiên cứu, không mô tả diễn biến giá.", items);
    }

    private static BriefSection portfolio(BriefEvidence evidence, ZoneId zone, Review review) {
        List<BriefItem> items = new ArrayList<>();
        if (evidence.portfolioId() == null) {
            items.add(BriefItem.note("Bản tin chung: không gắn với danh mục nào."));
            return section(BriefSectionKey.PORTFOLIO_UPDATES, "Cập nhật cho danh mục của tôi", null, items);
        }
        if (evidence.holdings().isEmpty()) {
            items.add(BriefItem.fact("Danh mục chưa có vị thế mở."));
            return section(BriefSectionKey.PORTFOLIO_UPDATES, "Cập nhật cho danh mục của tôi", null, items);
        }
        List<String> quiet = new ArrayList<>();
        for (HoldingView holding : evidence.holdings()) {
            String symbol = holding.symbol();
            List<Entry> about = evidence.entries().stream().filter(e -> e.heldSymbols().contains(symbol)).toList();
            AssessmentRef assessment = evidence.assessments().get(symbol);
            if (about.isEmpty() && assessment == null) {
                quiet.add(symbol);
                continue;
            }
            about.forEach(entry -> items.add(claim(entry, symbol, null)));
            if (review != null) {
                review.findings().getOrDefault(symbol.toUpperCase(Locale.ROOT), List.of()).forEach(items::add);
            }
            if (assessment != null) {
                items.add(new BriefItem(assessment.aiInterpretation() ? BriefItemKind.AI_INTERPRETATION : BriefItemKind.PORTFOLIO_FACT,
                        symbol + ": đánh giá tác động ngày " + DATE.format(assessment.createdAt().atZone(zone))
                                + " xếp vào nhóm " + assessment.category() + (assessment.aiInterpretation() ? " (có diễn giải AI)." : "."),
                        null, List.of(), symbol, null, null, null, null, assessment.assessmentId()));
            }
        }
        if (!quiet.isEmpty()) {
            items.add(BriefItem.fact("Không có nghiên cứu mới trong kỳ về: " + String.join(", ", quiet) + "."));
        }
        return section(BriefSectionKey.PORTFOLIO_UPDATES, "Cập nhật cho danh mục của tôi", null, items);
    }

    private static BriefSection opportunities(BriefEvidence evidence, Review review) {
        List<BriefItem> items = new ArrayList<>();
        List<Entry> others = evidence.entries().stream().filter(e -> e.relevance() == Relevance.OTHER)
                .sorted(Comparator.comparing((Entry e) -> e.otherTickers().size() == 1 || e.source().sourceTicker() != null ? 0 : 1))
                .limit(6).toList();
        for (Entry entry : others) {
            items.add(claim(entry, String.join(", ", entry.otherTickers()), null));
            if (review != null) {
                entry.otherTickers().forEach(t -> review.opportunities().getOrDefault(t.toUpperCase(Locale.ROOT), List.of()).forEach(items::add));
            }
        }
        if (items.isEmpty()) {
            items.add(BriefItem.fact("Không có nghiên cứu mới về mã ngoài danh mục trong kỳ."));
        }
        return section(BriefSectionKey.OPPORTUNITIES, "Cơ hội nghiên cứu",
                "Danh sách ưu tiên đọc thêm, không phải tín hiệu mua.", dedupe(items));
    }

    private static BriefSection risks(Map<String, List<View>> conflicts, Review review) {
        List<BriefItem> items = new ArrayList<>();
        conflicts.forEach((ticker, views) -> {
            StringBuilder text = new StringBuilder("Quan điểm khác nhau về ").append(ticker).append(": ");
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < views.size(); i++) {
                View view = views.get(i);
                text.append(i == 0 ? "" : "; ").append(view.brokerage()).append(" khuyến nghị «").append(view.rating().label()).append("»")
                        .append(view.publishedOn() == null ? "" : " (" + DATE.format(view.publishedOn()) + ")")
                        .append(" [").append(view.label()).append(']');
                labels.add(view.label());
            }
            text.append(". Đây là ý kiến của các công ty chứng khoán, Investary không chọn bên nào.");
            items.add(new BriefItem(BriefItemKind.SOURCE_CLAIM, text.toString(), EvidenceStatus.SUPPORTED, labels, ticker, null, null, null,
                    null, null));
        });
        if (review != null) {
            items.addAll(review.risks());
        }
        if (items.isEmpty()) {
            items.add(BriefItem.note("Không phát hiện quan điểm trái chiều rõ ràng giữa các nguồn mới (chỉ nhận diện nhãn khuyến nghị "
                    + "ghi rõ trong tài liệu)."));
        }
        return section(BriefSectionKey.RISKS_AND_CONFLICTS, "Rủi ro và quan điểm trái chiều", null, items);
    }

    private static BriefSection followUps(BriefEvidence evidence, Map<String, List<View>> conflicts, Review review) {
        List<BriefItem> items = new ArrayList<>();
        evidence.entries().stream().filter(e -> e.relevance() == Relevance.DIRECT).limit(5).forEach(entry -> {
            String label = entry.labels().getFirst();
            items.add(new BriefItem(BriefItemKind.SYSTEM_NOTE, "Đọc «" + title(entry) + "»" + by(entry) + " về "
                    + String.join(", ", entry.heldSymbols()) + " [" + label + "].", null, List.of(label), null, null, null, null, null, null));
        });
        conflicts.keySet().forEach(ticker -> items.add(BriefItem.note("So sánh các quan điểm khác nhau về " + ticker
                + " trong nguồn gốc trước khi dùng chúng.")));
        if (!evidence.pending().isEmpty()) {
            items.add(BriefItem.note("Kiểm tra " + evidence.pending().size() + " nguồn đã nhận trong kỳ nhưng chưa xử lý xong (danh sách ở "
                    + "phần nguồn)."));
        }
        List<String> unpriced = evidence.holdings().stream()
                .filter(h -> h.valuationStatus() == ValuationStatus.STALE || h.valuationStatus() == ValuationStatus.UNAVAILABLE)
                .map(HoldingView::symbol).toList();
        if (!unpriced.isEmpty()) {
            items.add(BriefItem.note("Cập nhật giá thủ công cho " + String.join(", ", unpriced) + ": giá hiện có đã cũ hoặc chưa có."));
        }
        if (review != null) {
            items.addAll(review.followUps());
        }
        if (items.isEmpty()) {
            items.add(BriefItem.note("Không có việc cần theo dõi thêm từ kỳ này."));
        }
        return section(BriefSectionKey.FOLLOW_UPS, "Việc nên làm tiếp",
                "Gợi ý đọc và kiểm tra dữ liệu; không phải lệnh mua, bán hay thay đổi tỷ trọng.",
                items.stream().limit(MAX_FOLLOW_UPS).toList());
    }

    private static BriefSection sources(BriefEvidence evidence, List<Limitation> limitations) {
        List<BriefItem> items = new ArrayList<>();
        if (!evidence.entries().isEmpty()) {
            Map<String, Long> byBrokerage = evidence.entries().stream()
                    .collect(Collectors.groupingBy(e -> e.source().brokerage() == null ? "không rõ" : e.source().brokerage(), TreeMap::new,
                            Collectors.counting()));
            List<LocalDate> dates = evidence.entries().stream().map(e -> e.source().publishedOn()).filter(Objects::nonNull).sorted().toList();
            StringBuilder text = new StringBuilder("Nguồn trong bản tin: ").append(byBrokerage.entrySet().stream()
                    .map(e -> e.getKey() + " (" + e.getValue() + ")").collect(Collectors.joining(", "))).append('.');
            if (!dates.isEmpty()) {
                text.append(" Ngày của nguồn từ ").append(DATE.format(dates.getFirst())).append(" đến ").append(DATE.format(dates.getLast()))
                        .append('.');
            }
            items.add(BriefItem.fact(text.toString()));
        }
        limitations.forEach(limitation -> items.add(BriefItem.note(limitation.message())));
        if (items.isEmpty()) {
            items.add(BriefItem.note("Không có nguồn mới và không có hạn chế nào cần lưu ý."));
        }
        return section(BriefSectionKey.SOURCES_AND_LIMITATIONS, "Nguồn và hạn chế", null, items);
    }

    private static void addSourceLimitations(BriefEvidence evidence, List<Limitation> notes, ZoneId zone) {
        if (evidence.portfolioId() == null) {
            notes.add(new Limitation("NO_PORTFOLIO", "Bản tin chung, không gắn với danh mục nào."));
        }
        for (Entry entry : evidence.entries()) {
            if (entry.oldPublication()) {
                notes.add(new Limitation("OLD_PUBLICATION", "«" + title(entry) + "» nhận trong kỳ nhưng " + dateText(entry)
                        + "; đây không phải nghiên cứu mới công bố."));
            }
            if (entry.source().publishedOn() == null) {
                notes.add(new Limitation("UNDATED_SOURCE", "«" + title(entry) + "» không có ngày công bố."));
            } else if ("EMAIL_SENT".equals(entry.source().dateBasis())) {
                notes.add(new Limitation("DATE_FROM_EMAIL", "Ngày của «" + title(entry) + "» là ngày gửi email, không phải ngày công bố."));
            }
            if (entry.lateArrival()) {
                notes.add(new Limitation("LATE_ARRIVAL", "«" + title(entry) + "» được nhập ngày "
                        + DATE.format(entry.source().importedAt().atZone(zone)) + " (trước kỳ này) nhưng đến kỳ này mới xử lý xong."));
            }
        }
        if (!evidence.omitted().isEmpty()) {
            notes.add(new Limitation("OMITTED_SOURCES", evidence.omitted().size()
                    + " nguồn mới không được đưa vào phần phân tích do giới hạn số nguồn hoặc độ dài; xem danh sách nguồn."));
        }
        if (!evidence.pending().isEmpty()) {
            Map<String, Long> states = evidence.pending().stream()
                    .collect(Collectors.groupingBy(PendingSource::state, TreeMap::new, Collectors.counting()));
            notes.add(new Limitation("PENDING_SOURCES", evidence.pending().size() + " nguồn nhập trong kỳ chưa tìm kiếm được ("
                    + states.entrySet().stream().map(e -> stateText(e.getKey()) + ": " + e.getValue()).collect(Collectors.joining(", "))
                    + "); chúng sẽ có trong bản tin của kỳ mà chúng được xử lý xong."));
        }
        if (evidence.portfolioId() != null && !evidence.profile().configured()) {
            notes.add(new Limitation("NO_PROFILE", "Chưa có hồ sơ nhà đầu tư; mức độ phù hợp với mục tiêu không được xét."));
        }
        if (evidence.holdings().stream().anyMatch(h -> h.valuationStatus() == ValuationStatus.STALE
                || h.valuationStatus() == ValuationStatus.UNAVAILABLE)) {
            notes.add(new Limitation("PRICES_STALE", "Một số mã chưa có giá hoặc giá thủ công đã cũ; bản tin không tính giá trị thị trường."));
        }
    }

    /** A brokerage statement: who, what, when, how relevant, and a verbatim excerpt from the cited chunk. */
    private static BriefItem claim(Entry entry, String symbol, String extra) {
        LabeledHit first = entry.hits().getFirst();
        List<String> terms = new ArrayList<>(entry.heldSymbols());
        terms.addAll(entry.otherTickers());
        String excerpt = shorten(Citations.of(first, terms.stream().map(Keywords::normalize).toList()).excerpt());
        StringBuilder text = new StringBuilder();
        if (symbol != null && !symbol.isBlank()) {
            text.append(symbol).append(": ");
        }
        text.append(entry.source().brokerage() == null ? "Nguồn" : entry.source().brokerage()).append(" — «").append(title(entry))
                .append("» (").append(dateText(entry));
        if (entry.relevance() == Relevance.DIRECT) {
            text.append("; liên quan trực tiếp");
        } else if (entry.relevance() == Relevance.MENTION) {
            text.append("; được nhắc tới cùng mã khác");
        }
        if (entry.oldPublication()) {
            text.append("; nhận trong kỳ, không phải mới công bố");
        }
        text.append("). Trích nguồn: “").append(excerpt).append("” [").append(first.label()).append(']');
        if (extra != null) {
            text.append(' ').append(extra);
        }
        return new BriefItem(BriefItemKind.SOURCE_CLAIM, text.toString(), EvidenceStatus.SUPPORTED, List.of(first.label()), symbol,
                entry.relevance(), entry.source().publishedOn(), entry.source().dateBasis(), entry.source().brokerage(), null);
    }

    private static String dateText(Entry entry) {
        LocalDate date = entry.source().publishedOn();
        if (date == null) {
            return "không rõ ngày công bố";
        }
        return ("EMAIL_SENT".equals(entry.source().dateBasis()) ? "email gửi ngày " : "công bố ngày ") + DATE.format(date);
    }

    private static String title(Entry entry) {
        String title = entry.source().title();
        return title == null || title.isBlank() ? "(không có tiêu đề)" : title.strip();
    }

    private static String by(Entry entry) {
        return entry.source().brokerage() == null ? "" : " của " + entry.source().brokerage();
    }

    private static String stateText(String state) {
        return switch (state) {
            case "NOT_TRACKED", "PENDING" -> "chờ xử lý";
            case "PROCESSING" -> "đang xử lý";
            case "FAILED" -> "xử lý lỗi";
            case "SKIPPED" -> "không trích xuất được nội dung";
            case "OUTDATED" -> "đang cập nhật chỉ mục";
            default -> state;
        };
    }

    /** Verbatim excerpt, shortened; brackets are replaced so source text can never look like a citation marker. */
    private static String shorten(String excerpt) {
        String value = excerpt.replace('[', '(').replace(']', ')').replaceAll("\\s+", " ").strip();
        return value.length() <= EXCERPT ? value : value.substring(0, EXCERPT - 1) + "…";
    }

    private static List<BriefItem> dedupe(List<BriefItem> items) {
        Map<String, BriefItem> unique = new LinkedHashMap<>();
        items.forEach(item -> unique.putIfAbsent(item.text(), item));
        return List.copyOf(unique.values());
    }

    private static BriefSection section(BriefSectionKey key, String title, String note, List<BriefItem> items) {
        EvidenceStatus status = items.stream().map(BriefItem::evidenceStatus).filter(Objects::nonNull)
                .reduce(EvidenceStatus.SUPPORTED, EvidenceStatus::atMost);
        boolean checked = items.stream().anyMatch(i -> i.evidenceStatus() != null);
        return new BriefSection(key, title, note, checked ? status : null, List.copyOf(items));
    }

    /**
     * @param evidenceStatus lowest section status, or null when no item needed evidence
     * @param citedLabels    labels referenced by any item
     */
    record Composition(BriefContent content, List<Limitation> limitations, EvidenceStatus evidenceStatus, Set<String> citedLabels) {
    }
}
