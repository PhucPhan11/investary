package com.investary.research.download;

import java.net.UnknownHostException;

/**
 * A host resolved to an address Investary must never connect to. Extends {@link UnknownHostException}
 * so it surfaces through the HTTP client's DNS resolution path unchanged.
 */
public class BlockedAddressException extends UnknownHostException {

    public BlockedAddressException(String message) {
        super(message);
    }
}
