package com.investary.email.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import com.investary.email.EmailProperties;

class LocalFileSystemEmailStorageTest {

    @TempDir
    Path root;

    @Test
    void storesUnderUtcYearAndMonth() throws IOException {
        LocalFileSystemEmailStorage storage = new LocalFileSystemEmailStorage(
                new EmailProperties(new EmailProperties.Storage(root), DataSize.ofMegabytes(1)));
        byte[] raw = "Subject: test\r\n\r\nbody".getBytes(StandardCharsets.US_ASCII);

        // 23:30 UTC on 31 October is already 1 November in Vietnam; the folder follows UTC.
        String key = storage.store(raw, Instant.parse("2026-10-31T23:30:00Z"));

        assertThat(key).startsWith("2026/10/").matches(LocalFileSystemEmailStorage.KEY_PATTERN);
        try (InputStream in = storage.load(key).getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(raw);
        }
        assertThat(storage.delete(key)).isTrue();
        assertThat(storage.delete(key)).isFalse();
    }
}
