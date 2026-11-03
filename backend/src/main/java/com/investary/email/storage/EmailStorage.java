package com.investary.email.storage;

import java.time.Instant;

import org.springframework.core.io.Resource;

/** Port for original EML files, the source of truth for imported emails (ADR 0003). */
public interface EmailStorage {

    /**
     * @param receivedAt import time; picks the {@code yyyy/MM} folder
     * @return the storage key of the new file
     */
    String store(byte[] raw, Instant receivedAt);

    Resource load(String storageKey);

    /**
     * @return false when the file was already missing
     */
    boolean delete(String storageKey);
}
