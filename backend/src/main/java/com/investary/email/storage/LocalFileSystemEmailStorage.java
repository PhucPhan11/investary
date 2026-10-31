package com.investary.email.storage;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.investary.common.storage.LocalFileStore;
import com.investary.email.EmailProperties;

/** Stores EMLs under {@code investary.email.storage.root} as {@code yyyy/MM/<uuid>.eml} (UTC month). */
@Component
public class LocalFileSystemEmailStorage implements EmailStorage {

    static final Pattern KEY_PATTERN =
            Pattern.compile("\\d{4}/\\d{2}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.eml");
    private static final DateTimeFormatter FOLDER = DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC);

    private final LocalFileStore files;

    public LocalFileSystemEmailStorage(EmailProperties properties) {
        this.files = new LocalFileStore(properties.storage().root(), KEY_PATTERN);
    }

    @Override
    public String store(byte[] raw, Instant receivedAt) {
        String key = FOLDER.format(receivedAt) + "/" + UUID.randomUUID() + ".eml";
        files.write(key, raw);
        return key;
    }

    @Override
    public Resource load(String storageKey) {
        return files.load(storageKey);
    }

    @Override
    public boolean delete(String storageKey) {
        return files.delete(storageKey);
    }
}
