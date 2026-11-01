package com.investary.automation.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Warnings for source rules that could import more than brokerage research. They are advice shown in the UI;
 * only {@link #PUBLIC_MAIL_DOMAIN} must be confirmed before saving.
 */
public final class SourceRuleWarnings {

    /** A sender domain of a public mailbox service matches every user of that service. */
    public static final String PUBLIC_MAIL_DOMAIN = "PUBLIC_MAIL_DOMAIN";
    /** Many sender domains in one rule. */
    public static final String MANY_DOMAINS = "MANY_DOMAINS";
    /** Domain-only senders and no subject keyword or Gmail query: every mail from those domains is imported. */
    public static final String NO_SUBJECT_FILTER = "NO_SUBJECT_FILTER";

    static final int MANY_DOMAINS_THRESHOLD = 10;

    private static final Set<String> PUBLIC_DOMAINS = Set.of("gmail.com", "googlemail.com", "yahoo.com", "yahoo.com.vn", "outlook.com",
            "hotmail.com", "live.com", "msn.com", "icloud.com", "me.com", "aol.com", "proton.me", "protonmail.com", "gmx.com",
            "mail.com", "yandex.com", "zoho.com");

    private SourceRuleWarnings() {
    }

    public static List<String> of(List<String> addresses, List<String> domains, List<String> keywords, String gmailQuery) {
        List<String> warnings = new ArrayList<>();
        List<String> safeDomains = domains == null ? List.of() : domains;
        if (!publicDomains(safeDomains).isEmpty()) {
            warnings.add(PUBLIC_MAIL_DOMAIN);
        }
        if (safeDomains.size() > MANY_DOMAINS_THRESHOLD) {
            warnings.add(MANY_DOMAINS);
        }
        boolean noFilter = (keywords == null || keywords.isEmpty()) && (gmailQuery == null || gmailQuery.isBlank());
        if (!safeDomains.isEmpty() && noFilter) {
            warnings.add(NO_SUBJECT_FILTER);
        }
        return warnings;
    }

    /** The public mailbox domains among {@code domains} (already normalised to lower case). */
    public static List<String> publicDomains(List<String> domains) {
        return domains.stream().filter(PUBLIC_DOMAINS::contains).toList();
    }
}
