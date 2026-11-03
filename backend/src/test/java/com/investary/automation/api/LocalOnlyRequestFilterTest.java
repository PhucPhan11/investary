package com.investary.automation.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalOnlyRequestFilterTest {

    final LocalOnlyRequestFilter filter = new LocalOnlyRequestFilter();

    static MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr("127.0.0.1");
        request.setServerName("localhost");
        return request;
    }

    MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void allowsLoopbackRequestsIncludingTheAngularProxy() throws Exception {
        MockHttpServletRequest proxied = request("POST", "/api/v1/integrations/gmail/sync");
        proxied.setRemoteAddr("0:0:0:0:0:0:0:1");
        proxied.addHeader("Origin", "http://localhost:4200");
        proxied.addHeader("Sec-Fetch-Site", "same-origin");
        assertThat(run(proxied).getStatus()).isEqualTo(200);

        MockHttpServletRequest callback = request("GET", "/api/v1/integrations/gmail/callback");
        callback.addHeader("Sec-Fetch-Site", "cross-site"); // top-level navigation back from Google
        assertThat(run(callback).getStatus()).isEqualTo(200);
    }

    @Test
    void refusesRemoteClientsRebindingHostsAndCrossSiteWrites() throws Exception {
        MockHttpServletRequest remote = request("GET", "/api/v1/integrations/gmail/status");
        remote.setRemoteAddr("192.168.1.20");
        MockHttpServletResponse response = run(remote);
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":\"LOCAL_ONLY\"");

        MockHttpServletRequest rebinding = request("GET", "/api/v1/automation/sources");
        rebinding.setServerName("attacker.example");
        assertThat(run(rebinding).getStatus()).isEqualTo(403);

        MockHttpServletRequest foreignOrigin = request("POST", "/api/v1/integrations/gmail/disconnect");
        foreignOrigin.addHeader("Origin", "https://evil.example");
        assertThat(run(foreignOrigin).getStatus()).isEqualTo(403);

        MockHttpServletRequest nullOrigin = request("DELETE", "/api/v1/automation/sources/x");
        nullOrigin.addHeader("Origin", "null");
        assertThat(run(nullOrigin).getStatus()).isEqualTo(403);

        MockHttpServletRequest crossSite = request("POST", "/api/v1/integrations/gmail/connect");
        crossSite.addHeader("Sec-Fetch-Site", "cross-site");
        assertThat(run(crossSite).getStatus()).isEqualTo(403);
    }

    @Test
    void leavesOtherEndpointsAlone() throws Exception {
        MockHttpServletRequest other = request("GET", "/api/v1/emails");
        other.setRemoteAddr("192.168.1.20");
        assertThat(run(other).getStatus()).isEqualTo(200);
    }

    @Test
    void neverResolvesHostNames() {
        assertThat(LocalOnlyRequestFilter.isLoopbackAddress("localhost")).isFalse();
        assertThat(LocalOnlyRequestFilter.isLoopbackAddress("127.0.0.2")).isTrue();
        assertThat(LocalOnlyRequestFilter.isLoopbackAddress("::1")).isTrue();
        assertThat(LocalOnlyRequestFilter.isLoopbackAddress("10.0.0.1")).isFalse();
    }
}
