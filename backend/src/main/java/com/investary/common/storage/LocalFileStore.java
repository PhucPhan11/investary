package com.investary.common.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

import org.springframework.core.io.PathResource;
import org.springframework.core.io.Resource;

/**
 * Stores files under a root directory by server-generated keys. Keys must match the store's pattern and
 * resolve inside the root; writes go to a temporary file first and are moved into place.
 */
public final class LocalFileStore {

    private final Path root;
    private final Pattern keyPattern;

    public LocalFileStore(Path root, Pattern keyPattern) {
        this.root = root.toAbsolutePath().normalize();
        this.keyPattern = keyPattern;
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new StorageException("Could not create the storage directory", e);
        }
    }

    public void write(String key, InputStream content) {
        Path target = resolve(key);
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            try (InputStream in = content) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            move(temp, target);
        } catch (IOException e) {
            deleteQuietly(temp);
            throw new StorageException("Failed to store content", e);
        }
    }

    public void write(String key, byte[] content) {
        write(key, new ByteArrayInputStream(content));
    }

    public Resource load(String key) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            throw new StorageException("Stored content is missing");
        }
        return new PathResource(file);
    }

    /**
     * @return false when there was nothing to delete
     */
    public boolean delete(String key) {
        try {
            return Files.deleteIfExists(resolve(key));
        } catch (IOException e) {
            throw new StorageException("Failed to delete stored content", e);
        }
    }

    private Path resolve(String key) {
        if (key == null || !keyPattern.matcher(key).matches()) {
            throw new StorageException("Invalid storage key");
        }
        Path file = root.resolve(key).normalize();
        if (!file.startsWith(root) || file.equals(root)) {
            throw new StorageException("Invalid storage key");
        }
        return file;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // Best effort: a leftover temporary file is harmless.
        }
    }
}
