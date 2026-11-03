package com.investary.automation.application;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

import com.investary.automation.domain.EmailSourceRule;

/**
 * Matches a verified sender address and subject against the enabled source rules (first match wins),
 * and builds the Gmail-side prefilter query for a rule. The local match is authoritative; Gmail's
 * search is only used to avoid fetching unrelated mail.
 */
public final class RuleMatcher {

    private RuleMatcher() {
    }

    public static Optional<EmailSourceRule> match(List<EmailSourceRule> rules, String senderAddress, String subject) {
        String domain = SenderVerifier.domainOf(senderAddress);
        return rules.stream()
                .filter(EmailSourceRule::isEnabled)
                .filter(rule -> senderMatches(rule, senderAddress, domain))
                .filter(rule -> subjectMatches(rule, subject))
                .findFirst();
    }

    static boolean senderMatches(EmailSourceRule rule, String address, String domain) {
        if (rule.getSenderAddresses().contains(address)) {
            return true;
        }
        return rule.getSenderDomains().stream().anyMatch(d -> domain.equals(d) || domain.endsWith("." + d));
    }

    static boolean subjectMatches(EmailSourceRule rule, String subject) {
        if (rule.getSubjectKeywords().isEmpty()) {
            return true;
        }
        String folded = fold(subject == null ? "" : subject);
        return rule.getSubjectKeywords().stream().map(RuleMatcher::fold).anyMatch(folded::contains);
    }

    /**
     * {@code from:(a@x.vn OR y.vn) [(<rule query>)] after:<epoch seconds>}. Gmail's {@code from:} on a domain
     * matches any address of that domain (and lookalikes such as {@code xy.vn}), which is why the local
     * check runs afterwards.
     */
    public static String gmailQuery(EmailSourceRule rule, Instant after) {
        List<String> senders = new ArrayList<>(rule.getSenderAddresses());
        senders.addAll(rule.getSenderDomains());
        StringBuilder query = new StringBuilder("from:(")
                .append(senders.stream().collect(Collectors.joining(" OR ")))
                .append(')');
        if (rule.getGmailQuery() != null && !rule.getGmailQuery().isBlank()) {
            query.append(" (").append(rule.getGmailQuery().strip()).append(')');
        }
        query.append(" after:").append(after.getEpochSecond());
        return query.toString();
    }

    /** Lower case without Vietnamese diacritics, so "Báo cáo" matches "bao cao". */
    static String fold(String text) {
        String decomposed = Normalizer.normalize(text.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
}
