package com.investary.automation.google;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.function.Consumer;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.investary.automation.GmailProperties;
import com.investary.automation.google.GoogleExceptions.GoogleTransientException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal HTTP access to Google's fixed, configured endpoints: timeouts, no redirects, no cookies.
 * Responses are returned as status + JSON so callers classify errors themselves. These URLs come from
 * configuration, never from email content, so the report download policy does not apply here.
 */
@Component
public class GoogleHttp {

    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;

    private final RestClient client;
    private final JsonMapper json;

    public GoogleHttp(GmailProperties properties, JsonMapper json) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.readTimeout());
        this.client = RestClient.builder().requestFactory(factory).build();
        this.json = json;
    }

    public Response get(URI uri, String accessToken) {
        return exchange(HttpMethod.GET, uri, headers -> headers.setBearerAuth(accessToken), null);
    }

    public Response postForm(URI uri, String formBody) {
        return exchange(HttpMethod.POST, uri,
                headers -> headers.set(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded"), formBody);
    }

    private Response exchange(HttpMethod method, URI uri, Consumer<HttpHeaders> headers, String body) {
        try {
            RestClient.RequestBodySpec spec = client.method(method).uri(uri).headers(headers);
            if (body != null) {
                spec.body(body);
            }
            return spec.exchange((request, response) -> {
                byte[] bytes;
                try (InputStream in = response.getBody()) {
                    bytes = in.readNBytes(MAX_BODY_BYTES);
                }
                JsonNode node = parse(bytes);
                return new Response(response.getStatusCode().value(), node,
                        response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            }, true);
        } catch (ResourceAccessException e) {
            throw new GoogleTransientException("GOOGLE_UNAVAILABLE", "Google could not be reached", e);
        }
    }

    private JsonNode parse(byte[] bytes) {
        if (bytes.length == 0) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(bytes);
        } catch (RuntimeException e) {
            return json.createObjectNode();
        }
    }

    /**
     * @param retryAfter raw {@code Retry-After} header, if any
     */
    public record Response(int status, JsonNode body, String retryAfter) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        /** OAuth {@code error} or Gmail {@code error.errors[0].reason}, or empty. */
        public String errorReason() {
            JsonNode error = body.path("error");
            if (error.isString()) {
                return error.asString();
            }
            JsonNode reason = error.path("errors").path(0).path("reason");
            if (reason.isString()) {
                return reason.asString();
            }
            return error.path("status").asString("");
        }
    }
}
