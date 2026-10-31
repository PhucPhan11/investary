package com.investary.research.link;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Identifies brokerages by e-mail or link domain, from configuration rather than code, so adding a
 * provider (SSI, VNDIRECT, VCBS, ...) does not require a release.
 */
@Component
public class BrokerageRegistry {

    private final List<ResearchProperties.Brokerage> brokerages;

    public BrokerageRegistry(ResearchProperties properties) {
        this.brokerages = properties.brokerages();
    }

    public Optional<String> identifySender(String senderAddress) {
        if (senderAddress == null || senderAddress.indexOf('@') < 0) {
            return Optional.empty();
        }
        return identifyHost(senderAddress.substring(senderAddress.lastIndexOf('@') + 1));
    }

    public Optional<String> identifyHost(String host) {
        if (host == null) {
            return Optional.empty();
        }
        return brokerages.stream()
                .filter(brokerage -> brokerage.domains().stream().anyMatch(domain -> matches(host, domain)))
                .map(ResearchProperties.Brokerage::name)
                .findFirst();
    }

    public List<String> domains() {
        return brokerages.stream().flatMap(b -> b.domains().stream()).toList();
    }

    public static boolean matches(String host, String domain) {
        String h = host.toLowerCase(Locale.ROOT);
        String d = domain.toLowerCase(Locale.ROOT).strip();
        return !d.isEmpty() && (h.equals(d) || h.endsWith("." + d));
    }
}
