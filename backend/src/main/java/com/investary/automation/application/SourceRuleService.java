package com.investary.automation.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.investary.automation.application.AutomationExceptions.DuplicateSourceRuleException;
import com.investary.automation.application.AutomationExceptions.InvalidSourceRuleException;
import com.investary.automation.application.AutomationExceptions.SourceRuleNotFoundException;
import com.investary.automation.domain.EmailSourceRule;
import com.investary.automation.persistence.EmailSourceRuleRepository;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/**
 * CRUD for brokerage source rules. Addresses and domains are normalised to lower case and validated;
 * the optional Gmail query is checked by {@link GmailQueryValidator}.
 */
@Service
public class SourceRuleService {

    static final int MAX_SENDERS = 50;
    static final int MAX_KEYWORDS = 20;
    static final int MAX_KEYWORD_LENGTH = 100;
    private static final Pattern DOMAIN = Pattern.compile(
            "^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$");

    private final EmailSourceRuleRepository rules;
    private final Clock clock;

    public SourceRuleService(EmailSourceRuleRepository rules, Clock clock) {
        this.rules = rules;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SourceRuleView> list() {
        return rules.findAllOrdered().stream().map(SourceRuleView::of).toList();
    }

    @Transactional(readOnly = true)
    public SourceRuleView get(UUID id) {
        return SourceRuleView.of(require(id));
    }

    @Transactional
    public SourceRuleView create(SourceRuleCommand command) {
        Instant now = clock.instant();
        EmailSourceRule rule = new EmailSourceRule(now);
        return save(rule, command, now);
    }

    @Transactional
    public SourceRuleView update(UUID id, SourceRuleCommand command) {
        return save(require(id), command, clock.instant());
    }

    @Transactional
    public void delete(UUID id) {
        rules.delete(require(id));
    }

    private SourceRuleView save(EmailSourceRule rule, SourceRuleCommand command, Instant now) {
        Normalized normalized = normalize(command);
        String name = command.name().strip();
        rules.findByNameIgnoreCase(name)
                .filter(existing -> !existing.getId().equals(rule.getId()))
                .ifPresent(existing -> {
                    throw new DuplicateSourceRuleException(name);
                });
        rule.update(name, normalized.addresses(), normalized.domains(), normalized.keywords(), normalized.gmailQuery(),
                command.enabled() == null || command.enabled(),
                command.autoImport() == null || command.autoImport(),
                command.autoPdfDownload() != null && command.autoPdfDownload(), now);
        try {
            return SourceRuleView.of(rules.saveAndFlush(rule));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateSourceRuleException(name);
        }
    }

    private EmailSourceRule require(UUID id) {
        return rules.findById(id).orElseThrow(() -> new SourceRuleNotFoundException(id));
    }

    static Normalized normalize(SourceRuleCommand command) {
        Map<String, String> errors = new LinkedHashMap<>();
        Set<String> addresses = new LinkedHashSet<>();
        for (String raw : distinct(command.senderAddresses())) {
            String address = raw.toLowerCase(Locale.ROOT);
            if (!validAddress(address)) {
                errors.putIfAbsent("senderAddresses", "'" + abbreviate(raw) + "' is not a valid email address");
            }
            addresses.add(address);
        }
        Set<String> domains = new LinkedHashSet<>();
        for (String raw : distinct(command.senderDomains())) {
            String domain = raw.toLowerCase(Locale.ROOT).replaceFirst("^@", "");
            if (!DOMAIN.matcher(domain).matches()) {
                errors.putIfAbsent("senderDomains", "'" + abbreviate(raw) + "' is not a valid domain");
            }
            domains.add(domain);
        }
        if (addresses.isEmpty() && domains.isEmpty()) {
            errors.putIfAbsent("senderAddresses", "add at least one sender address or domain");
        }
        if (addresses.size() > MAX_SENDERS) {
            errors.putIfAbsent("senderAddresses", "at most " + MAX_SENDERS + " addresses");
        }
        if (domains.size() > MAX_SENDERS) {
            errors.putIfAbsent("senderDomains", "at most " + MAX_SENDERS + " domains");
        }
        List<String> keywords = distinct(command.subjectKeywords());
        if (keywords.size() > MAX_KEYWORDS) {
            errors.putIfAbsent("subjectKeywords", "at most " + MAX_KEYWORDS + " keywords");
        }
        if (keywords.stream().anyMatch(k -> k.length() > MAX_KEYWORD_LENGTH)) {
            errors.putIfAbsent("subjectKeywords", "keywords must be at most " + MAX_KEYWORD_LENGTH + " characters");
        }
        String gmailQuery = command.gmailQuery() == null || command.gmailQuery().isBlank() ? null : command.gmailQuery().strip();
        GmailQueryValidator.problem(gmailQuery).ifPresent(problem -> errors.putIfAbsent("gmailQuery", problem));
        if (!errors.isEmpty()) {
            throw new InvalidSourceRuleException(errors);
        }
        return new Normalized(List.copyOf(addresses), List.copyOf(domains), keywords, gmailQuery);
    }

    private static boolean validAddress(String address) {
        if (address.length() > 320 || address.indexOf('@') <= 0 || address.chars().anyMatch(Character::isWhitespace)) {
            return false;
        }
        try {
            new InternetAddress(address, true);
        } catch (AddressException e) {
            return false;
        }
        return DOMAIN.matcher(SenderVerifier.domainOf(address)).matches();
    }

    private static List<String> distinct(List<String> values) {
        if (values == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                result.add(value.strip());
            }
        }
        return List.copyOf(result);
    }

    private static String abbreviate(String value) {
        return value.length() > 60 ? value.substring(0, 60) + "…" : value;
    }

    record Normalized(List<String> addresses, List<String> domains, List<String> keywords, String gmailQuery) {
    }

    /**
     * @param enabled          default true
     * @param autoImport       default true
     * @param autoPdfDownload  default false; stored only in Milestone 1
     */
    public record SourceRuleCommand(String name, List<String> senderAddresses, List<String> senderDomains,
                                    List<String> subjectKeywords, String gmailQuery, Boolean enabled, Boolean autoImport,
                                    Boolean autoPdfDownload) {
    }

    public record SourceRuleView(UUID id, String name, List<String> senderAddresses, List<String> senderDomains,
                                 List<String> subjectKeywords, String gmailQuery, boolean enabled, boolean autoImport,
                                 boolean autoPdfDownload, Instant createdAt, Instant updatedAt) {

        static SourceRuleView of(EmailSourceRule rule) {
            return new SourceRuleView(rule.getId(), rule.getName(), rule.getSenderAddresses(), rule.getSenderDomains(),
                    rule.getSubjectKeywords(), rule.getGmailQuery(), rule.isEnabled(), rule.isAutoImport(),
                    rule.isAutoPdfDownload(), rule.getCreatedAt(), rule.getUpdatedAt());
        }
    }
}
