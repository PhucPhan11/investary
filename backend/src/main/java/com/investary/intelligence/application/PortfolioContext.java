package com.investary.intelligence.application;

import java.math.BigDecimal;
import java.util.List;

import com.investary.investor.application.GoalView;
import com.investary.investor.application.ProfileView;
import com.investary.knowledge.assistant.PromptBuilder;
import com.investary.portfolio.application.PortfolioViews.HoldingView;
import com.investary.portfolio.application.PortfolioViews.PortfolioSnapshot;
import com.investary.portfolio.application.PortfolioViews.PortfolioSummary;

/**
 * Renders backend-computed portfolio facts, the investor profile and goals as plain text for prompts.
 * Numbers are written plainly (no grouping) so the citation validator can recognise them when the model
 * repeats them. User-entered text is neutralised like source text.
 */
final class PortfolioContext {

    private PortfolioContext() {
    }

    static String facts(PortfolioSnapshot snapshot) {
        PortfolioSummary summary = snapshot.summary();
        StringBuilder text = new StringBuilder();
        text.append("Danh mục: ").append(PromptBuilder.neutralise(summary.name())).append(" (tiền tệ ").append(summary.currency()).append(")\n");
        text.append("Ngày định giá: ").append(summary.valuationDate()).append("; trạng thái định giá: ").append(summary.valuationStatus()).append('\n');
        text.append("Số mã đang nắm giữ: ").append(summary.openHoldings()).append("; tổng giá vốn: ").append(plain(summary.totalCostBasis()));
        if (summary.totalMarketValue() != null) {
            text.append("; tổng giá trị thị trường (theo giá nhập thủ công): ").append(plain(summary.totalMarketValue()))
                    .append("; lãi/lỗ chưa thực hiện: ").append(plain(summary.unrealizedPnl()));
        } else {
            text.append("; tổng giá trị thị trường: không có (thiếu giá cho một số mã)");
        }
        text.append("; lãi/lỗ đã thực hiện: ").append(plain(summary.realizedPnl())).append('\n');
        for (HoldingView holding : snapshot.holdings()) {
            text.append("- ").append(holding.symbol()).append(" (").append(PromptBuilder.neutralise(holding.name())).append(", ")
                    .append(holding.instrumentType()).append("): số lượng ").append(plain(holding.quantity()))
                    .append(", giá vốn bình quân ").append(plain(holding.averageCost()))
                    .append(", tổng giá vốn ").append(plain(holding.costBasis()))
                    .append(", tỷ trọng theo giá vốn ").append(plain(holding.costWeightPct())).append('%');
            if (holding.latestPrice() != null) {
                text.append(", giá nhập thủ công ").append(plain(holding.latestPrice())).append(" ngày ").append(holding.priceAsOf())
                        .append(holding.valuationStatus().name().equals("STALE") ? " (đã cũ)" : "");
            } else {
                text.append(", chưa có giá thị trường");
            }
            text.append('\n');
        }
        return text.toString();
    }

    static String profile(ProfileView profile) {
        if (!profile.configured()) {
            return "Người dùng chưa khai báo hồ sơ nhà đầu tư.\n";
        }
        StringBuilder text = new StringBuilder();
        line(text, "Kinh nghiệm", profile.experienceLevel());
        line(text, "Khẩu vị rủi ro", profile.riskTolerance());
        line(text, "Kỳ hạn đầu tư", profile.investmentHorizon());
        line(text, "Mục tiêu", profile.objectives());
        line(text, "Loại tài sản ưa thích", profile.preferredInstruments().isEmpty() ? null : String.join(", ", profile.preferredInstruments()));
        line(text, "Hạn chế", profile.restrictions());
        line(text, "Nhu cầu thanh khoản", profile.liquidityNeeds());
        return text.toString();
    }

    static String goals(List<GoalView> goals) {
        if (goals.isEmpty()) {
            return "Chưa có mục tiêu nào.\n";
        }
        StringBuilder text = new StringBuilder();
        for (GoalView goal : goals) {
            text.append("- ").append(PromptBuilder.neutralise(goal.name())).append(" (ưu tiên ").append(goal.priority()).append(')');
            if (goal.targetAmount() != null) {
                text.append(", số tiền mục tiêu ").append(plain(goal.targetAmount())).append(' ').append(goal.currency());
            }
            if (goal.targetDate() != null) {
                text.append(", thời hạn ").append(goal.targetDate());
            }
            text.append('\n');
        }
        return text.toString();
    }

    static String plain(BigDecimal value) {
        if (value == null) {
            return "không có";
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    private static void line(StringBuilder text, String label, Object value) {
        if (value != null) {
            text.append(label).append(": ").append(PromptBuilder.neutralise(value.toString())).append('\n');
        }
    }
}
