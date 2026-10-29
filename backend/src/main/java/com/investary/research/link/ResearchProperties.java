package com.investary.research.link;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param brokerages known research providers; their domains identify senders and form the download allowlist
 */
@ConfigurationProperties("investary.research")
public record ResearchProperties(List<Brokerage> brokerages) {

    public ResearchProperties {
        brokerages = brokerages == null ? List.of() : List.copyOf(brokerages);
    }

    /**
     * @param domains registrable domains; sub-domains match too ({@code tcbs.com.vn} covers {@code static.tcbs.com.vn})
     */
    public record Brokerage(String name, List<String> domains) {

        public Brokerage {
            domains = domains == null ? List.of() : List.copyOf(domains);
        }
    }
}
