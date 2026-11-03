package com.investary.document.storage;

import java.io.InputStream;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.investary.common.storage.LocalFileStore;

/** Stores PDFs flat under {@code investary.storage.root} as {@code <uuid>.pdf}. */
@Component
public class LocalFileSystemDocumentStorage implements DocumentStorage {

    static final Pattern KEY_PATTERN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.pdf");

    private final LocalFileStore files;

    public LocalFileSystemDocumentStorage(DocumentStorageProperties properties) {
        this.files = new LocalFileStore(properties.root(), KEY_PATTERN);
    }

    @Override
    public String store(InputStream content) {
        String key = UUID.randomUUID() + ".pdf";
        files.write(key, content);
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
