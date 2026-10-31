package com.investary.automation.google;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import com.investary.automation.GmailProperties;
import com.investary.automation.google.GoogleExceptions.GoogleApiException;
import com.investary.automation.google.GoogleExceptions.GoogleTransientException;
import com.investary.automation.google.GoogleExceptions.HistoryExpiredException;
import com.investary.automation.google.GoogleExceptions.MessageNotFoundException;
import com.investary.automation.google.GoogleHttp.Response;

import tools.jackson.databind.JsonNode;

/**
 * The few read-only Gmail API calls the sync needs ({@code users.getProfile}, {@code messages.list},
 * {@code messages.get}, {@code history.list}).
 * <ul>
 *   <li>401: the access token is refreshed once and the call repeated</li>
 *   <li>429, 5xx and 403 {@code rateLimitExceeded}/{@code userRateLimitExceeded}: exponential backoff that honours
 *       {@code Retry-After}, at most {@code max-retries} times, then {@link GoogleTransientException}</li>
 *   <li>404 on {@code history.list}: {@link HistoryExpiredException}; on a message: {@link MessageNotFoundException}</li>
 * </ul>
 */
@Component
public class GmailApiClient {

    private static final Logger log = LoggerFactory.getLogger(GmailApiClient.class);
    private static final Set<String> RATE_LIMIT_REASONS = Set.of("rateLimitExceeded", "userRateLimitExceeded");
    private static final List<String> METADATA_HEADERS = List.of("From", "Subject", "Authentication-Results");

    private final GmailProperties properties;
    private final GoogleHttp http;

    public GmailApiClient(GmailProperties properties, GoogleHttp http) {
        this.properties = properties;
        this.http = http;
    }

    public Profile profile(TokenSource tokens) {
        JsonNode body = call(tokens, uri("/gmail/v1/users/me/profile", Map.of()), Target.OTHER);
        return new Profile(body.path("emailAddress").asString(""), historyId(body));
    }

    /** {@code messages.list} (spam and trash excluded by Gmail's default). */
    public MessagePage listMessages(TokenSource tokens, String query, String pageToken) {
        Map<String, String> params = new HashMap<>();
        params.put("q", query);
        params.put("maxResults", Integer.toString(properties.sync().pageSize()));
        if (pageToken != null) {
            params.put("pageToken", pageToken);
        }
        JsonNode body = call(tokens, uri("/gmail/v1/users/me/messages", params), Target.OTHER);
        List<MessageRef> refs = new ArrayList<>();
        for (JsonNode message : body.path("messages")) {
            refs.add(new MessageRef(message.path("id").asString(), message.path("threadId").asString(null)));
        }
        return new MessagePage(refs, text(body, "nextPageToken"));
    }

    /** {@code history.list} restricted to added messages. */
    public HistoryPage listHistory(TokenSource tokens, String startHistoryId, String pageToken) {
        Map<String, String> params = new HashMap<>();
        params.put("startHistoryId", startHistoryId);
        params.put("historyTypes", "messageAdded");
        params.put("maxResults", "500");
        if (pageToken != null) {
            params.put("pageToken", pageToken);
        }
        JsonNode body = call(tokens, uri("/gmail/v1/users/me/history", params), Target.HISTORY);
        List<HistoryRecord> records = new ArrayList<>();
        for (JsonNode record : body.path("history")) {
            List<MessageRef> added = new ArrayList<>();
            for (JsonNode item : record.path("messagesAdded")) {
                JsonNode message = item.path("message");
                added.add(new MessageRef(message.path("id").asString(), message.path("threadId").asString(null)));
            }
            records.add(new HistoryRecord(record.path("id").asString(), added));
        }
        return new HistoryPage(records, text(body, "nextPageToken"), historyId(body));
    }

    /** Headers needed to decide whether a message matches a rule, plus size and labels. */
    public MessageMetadata metadata(TokenSource tokens, String messageId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.apiBaseUrl())
                .path("/gmail/v1/users/me/messages/{id}")
                .queryParam("format", "metadata");
        METADATA_HEADERS.forEach(h -> builder.queryParam("metadataHeaders", h));
        URI uri = builder.encode().buildAndExpand(Map.of("id", messageId)).toUri();
        JsonNode body = call(tokens, uri, Target.MESSAGE);
        List<Header> headers = new ArrayList<>();
        for (JsonNode header : body.path("payload").path("headers")) {
            headers.add(new Header(header.path("name").asString(""), header.path("value").asString("")));
        }
        List<String> labels = new ArrayList<>();
        body.path("labelIds").forEach(l -> labels.add(l.asString()));
        long internalDate = body.path("internalDate").asLong(0);
        return new MessageMetadata(messageId, text(body, "threadId"), historyId(body),
                internalDate > 0 ? Instant.ofEpochMilli(internalDate) : null,
                body.path("sizeEstimate").asLong(0), labels, headers);
    }

    /** The original RFC 822 bytes ({@code format=raw}, base64url-decoded). */
    public byte[] raw(TokenSource tokens, String messageId) {
        URI uri = UriComponentsBuilder.fromUriString(properties.apiBaseUrl())
                .path("/gmail/v1/users/me/messages/{id}")
                .queryParam("format", "raw")
                .encode().buildAndExpand(Map.of("id", messageId)).toUri();
        JsonNode body = call(tokens, uri, Target.MESSAGE);
        String raw = body.path("raw").asString(null);
        if (raw == null || raw.isEmpty()) {
            throw new GoogleApiException("INVALID_RESPONSE", "Gmail returned no raw message");
        }
        try {
            return Base64.getUrlDecoder().decode(raw);
        } catch (IllegalArgumentException e) {
            throw new GoogleApiException("INVALID_RESPONSE", "Gmail returned an undecodable raw message");
        }
    }

    private JsonNode call(TokenSource tokens, URI uri, Target target) {
        boolean refreshed = false;
        int attempt = 0;
        while (true) {
            Response response = http.get(uri, tokens.accessToken(refreshed));
            if (response.ok()) {
                return response.body();
            }
            int status = response.status();
            String reason = response.errorReason();
            if (status == 401 && !refreshed) {
                refreshed = true;
                continue;
            }
            if (status == 404 && target == Target.HISTORY) {
                throw new HistoryExpiredException();
            }
            if (status == 404 && target == Target.MESSAGE) {
                throw new MessageNotFoundException();
            }
            boolean rateLimited = status == 429 || status == 403 && RATE_LIMIT_REASONS.contains(reason);
            if (rateLimited || status >= 500) {
                if (attempt >= properties.maxRetries()) {
                    throw new GoogleTransientException(rateLimited ? "RATE_LIMITED" : "GMAIL_UNAVAILABLE",
                            rateLimited ? "Gmail rate limit still exceeded after " + attempt + " retries"
                                    : "Gmail answered status " + status + " after " + attempt + " retries");
                }
                Duration wait = backoff(attempt, response.retryAfter());
                log.info("Gmail call will be retried status={} attempt={} waitMs={}", status, attempt + 1, wait.toMillis());
                sleep(wait);
                attempt++;
                continue;
            }
            if (status == 401) {
                throw new GoogleApiException("UNAUTHORIZED", "Gmail rejected the access token");
            }
            if (status == 403) {
                throw new GoogleApiException("FORBIDDEN", "Gmail denied access (status 403" + (reason.matches("[A-Za-z]{1,40}") ? ", " + reason : "") + ")");
            }
            throw new GoogleApiException("GMAIL_ERROR", "Gmail answered status " + status);
        }
    }

    Duration backoff(int attempt, String retryAfter) {
        Duration wait = properties.retryBackoff().multipliedBy(1L << Math.min(attempt, 10));
        if (retryAfter != null && retryAfter.strip().matches("\\d{1,6}")) {
            wait = Duration.ofSeconds(Long.parseLong(retryAfter.strip()));
        }
        return wait.compareTo(properties.maxRetryWait()) > 0 ? properties.maxRetryWait() : wait;
    }

    private static void sleep(Duration wait) {
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleTransientException("INTERRUPTED", "Gmail sync was interrupted", e);
        }
    }

    private URI uri(String path, Map<String, String> params) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(properties.apiBaseUrl()).path(path);
        params.keySet().forEach(name -> builder.queryParam(name, "{" + name + "}"));
        return builder.encode().buildAndExpand(params).toUri();
    }

    private static String historyId(JsonNode body) {
        JsonNode node = body.path("historyId");
        String value = node.isNumber() ? node.asString() : node.asString(null);
        return value != null && value.matches("\\d{1,20}") ? value : null;
    }

    private static String text(JsonNode body, String field) {
        JsonNode node = body.path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private enum Target { HISTORY, MESSAGE, OTHER }

    /** Supplies a valid access token; {@code forceRefresh} after Gmail rejected the current one. */
    @FunctionalInterface
    public interface TokenSource {
        String accessToken(boolean forceRefresh);
    }

    public record Profile(String emailAddress, String historyId) {
    }

    public record MessageRef(String id, String threadId) {
    }

    public record MessagePage(List<MessageRef> messages, String nextPageToken) {
    }

    /**
     * @param id the history record id; the cursor may advance to it once its messages are handled
     */
    public record HistoryRecord(String id, List<MessageRef> messagesAdded) {
    }

    /**
     * @param historyId the mailbox's current history id (store it when there is no next page)
     */
    public record HistoryPage(List<HistoryRecord> history, String nextPageToken, String historyId) {
    }

    public record Header(String name, String value) {
    }

    public record MessageMetadata(String id, String threadId, String historyId, Instant internalDate, long sizeEstimate,
                                  List<String> labelIds, List<Header> headers) {

        public List<String> headers(String name) {
            return headers.stream().filter(h -> h.name().equalsIgnoreCase(name)).map(Header::value).toList();
        }

        public boolean hasLabel(String label) {
            return labelIds.stream().anyMatch(l -> l.toUpperCase(Locale.ROOT).equals(label));
        }
    }
}
