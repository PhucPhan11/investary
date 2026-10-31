package com.investary.automation.api;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Credential-bearing endpoints (Gmail integration, source rules) are local-only, because Investary has
 * no authentication:
 * <ul>
 *   <li>the client address must be loopback</li>
 *   <li>the {@code Host} must be a loopback name (stops DNS-rebinding pages from reaching the API)</li>
 *   <li>state-changing requests must not come from another site: a present {@code Origin} must be a
 *       loopback origin, and {@code Sec-Fetch-Site: cross-site} is refused</li>
 * </ul>
 * Anyone on this machine can still use them; see the known limitations.
 */
@Component
public class LocalOnlyRequestFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LocalOnlyRequestFilter.class);
    private static final Set<String> PROTECTED_PREFIXES = Set.of("/api/v1/integrations/", "/api/v1/automation/");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]", "0:0:0:0:0:0:0:1");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PROTECTED_PREFIXES.stream().noneMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String problem = problem(request);
        if (problem != null) {
            log.warn("Refused non-local request to {} reason={}", request.getRequestURI(), problem);
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("""
                    {"type":"about:blank","title":"Forbidden","status":403,\
                    "detail":"This endpoint is only available from this computer (http://localhost)","code":"LOCAL_ONLY"}""");
            return;
        }
        chain.doFilter(request, response);
    }

    static String problem(HttpServletRequest request) {
        if (!isLoopbackAddress(request.getRemoteAddr())) {
            return "remote-address";
        }
        if (!isLoopbackHost(request.getServerName())) {
            return "host";
        }
        if (!SAFE_METHODS.contains(request.getMethod().toUpperCase(Locale.ROOT))) {
            String origin = request.getHeader("Origin");
            if (origin != null && !isLoopbackOrigin(origin)) {
                return "origin";
            }
            String fetchSite = request.getHeader("Sec-Fetch-Site");
            if (fetchSite != null && fetchSite.equalsIgnoreCase("cross-site")) {
                return "fetch-site";
            }
        }
        return null;
    }

    static boolean isLoopbackAddress(String address) {
        if (address == null || address.isBlank() || !address.matches("[0-9a-fA-F:.%\\[\\]]+")) {
            return false; // only IP literals: never trigger a DNS lookup
        }
        try {
            return InetAddress.getByName(address.replace("[", "").replace("]", "")).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static boolean isLoopbackHost(String host) {
        return host != null && LOOPBACK_HOSTS.contains(host.toLowerCase(Locale.ROOT));
    }

    private static boolean isLoopbackOrigin(String origin) {
        try {
            URI uri = URI.create(origin);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && isLoopbackHost(uri.getHost());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
