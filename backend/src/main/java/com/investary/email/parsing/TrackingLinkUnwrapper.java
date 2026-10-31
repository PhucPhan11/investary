package com.investary.email.parsing;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.investary.common.web.UrlCodec;

/**
 * Recovers the real destination of click-tracking links whose target is embedded in the URL,
 * so Investary can classify and fetch the destination without contacting the tracker.
 * Trackers that only store an opaque id cannot be unwrapped; those links keep their original URL.
 */
@Component
public class TrackingLinkUnwrapper {

    /** Amazon SES click tracking: {@code https://<id>.r.<region>.awstrack.me/L0/<url-encoded target>/1/<message>/<signature>}. */
    private static final Pattern AWS_SES = Pattern.compile("^/L\\d+/([^/]+)/.*$");

    private final List<Function<URI, Optional<String>>> rules = List.of(TrackingLinkUnwrapper::awsSes);

    /**
     * @return the embedded http(s) destination, or the input unchanged when no rule applies
     */
    public String unwrap(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return url;
        }
        for (Function<URI, Optional<String>> rule : rules) {
            Optional<String> target = rule.apply(uri);
            if (target.isPresent()) {
                return target.get();
            }
        }
        return url;
    }

    private static Optional<String> awsSes(URI uri) {
        String host = uri.getHost();
        if (host == null || !host.toLowerCase(Locale.ROOT).endsWith(".awstrack.me") || uri.getRawPath() == null) {
            return Optional.empty();
        }
        Matcher matcher = AWS_SES.matcher(uri.getRawPath());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String target = UrlCodec.percentDecode(matcher.group(1));
        String lower = target.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
            return Optional.empty();
        }
        try {
            URI.create(target);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(target);
    }
}
