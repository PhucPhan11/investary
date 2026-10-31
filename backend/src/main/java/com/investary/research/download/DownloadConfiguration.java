package com.investary.research.download;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class DownloadConfiguration {

    /**
     * HTTP client used only for research downloads: no proxy, no cookies, no automatic redirects or
     * retries, and an address-checking DNS resolver.
     */
    @Bean(destroyMethod = "close")
    CloseableHttpClient reportDownloadHttpClient(DownloadProperties properties) {
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new RestrictedAddressDnsResolver(properties.allowPrivateNetworks()))
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.of(properties.connectTimeout()))
                        .setSocketTimeout(Timeout.of(properties.readTimeout()))
                        .build())
                .setMaxConnTotal(properties.worker().concurrency() * 2)
                .setMaxConnPerRoute(properties.worker().concurrency())
                .build();
        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(properties.readTimeout()))
                        .setConnectionRequestTimeout(Timeout.of(properties.connectTimeout()))
                        .build())
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableAuthCaching()
                .setUserAgent(properties.userAgent())
                .build();
    }
}
