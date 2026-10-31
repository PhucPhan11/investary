package com.investary.automation.application;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

/**
 * Determines the sender a source rule is matched against: the addr-spec of the single {@code From}
 * mailbox. The display name is never used (anyone can write "TCBS" there). A message whose {@code From}
 * holds several or no valid mailboxes has no verified sender.
 * <p>
 * Gmail prepends its own {@code Authentication-Results: mx.google.com; …} header. When that header
 * reports {@code dmarc=fail}, the {@code From} domain was spoofed and the message is rejected. Headers
 * further down (which a sender can forge) are ignored.
 */
public final class SenderVerifier {

    private static final Pattern DMARC_FAIL = Pattern.compile("(?i)(^|[;\\s])dmarc=fail\\b");
    private static final String GMAIL_AUTHSERV_ID = "mx.google.com";

    private SenderVerifier() {
    }

    /**
     * @param fromHeaders all {@code From} header values of the message (normally exactly one)
     */
    public static Optional<String> senderAddress(List<String> fromHeaders) {
        if (fromHeaders.size() != 1) {
            return Optional.empty();
        }
        try {
            InternetAddress[] addresses = InternetAddress.parseHeader(fromHeaders.getFirst(), true);
            if (addresses.length != 1 || addresses[0].isGroup()) {
                return Optional.empty();
            }
            addresses[0].validate();
            String address = addresses[0].getAddress();
            if (address == null || address.indexOf('@') <= 0) {
                return Optional.empty();
            }
            return Optional.of(address.toLowerCase(Locale.ROOT));
        } catch (AddressException e) {
            return Optional.empty();
        }
    }

    /**
     * @param authenticationResults all {@code Authentication-Results} header values, top first
     * @return true if Gmail's own (topmost {@code mx.google.com}) result says DMARC failed
     */
    public static boolean failedDmarc(List<String> authenticationResults) {
        return authenticationResults.stream()
                .filter(value -> value.strip().toLowerCase(Locale.ROOT).startsWith(GMAIL_AUTHSERV_ID))
                .findFirst()
                .map(value -> DMARC_FAIL.matcher(value).find())
                .orElse(false);
    }

    public static String domainOf(String address) {
        return address.substring(address.lastIndexOf('@') + 1);
    }
}
