package com.investary.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

class LocalFileStoreTest {

    @TempDir
    Path root;

    LocalFileStore store;

    @BeforeEach
    void setUp() {
        store = new LocalFileStore(root, Pattern.compile("[a-z0-9./]+\\.txt"));
    }

    @Test
    void writesLoadsAndDeletes() throws IOException {
        store.write("a/b/file.txt", "hello".getBytes(StandardCharsets.UTF_8));

        Resource loaded = store.load("a/b/file.txt");
        try (InputStream in = loaded.getInputStream()) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("hello");
        }
        assertThat(store.delete("a/b/file.txt")).isTrue();
        assertThat(store.delete("a/b/file.txt")).isFalse();
    }

    @Test
    void leavesNoTemporaryFiles() throws IOException {
        store.write("file.txt", "x".getBytes(StandardCharsets.UTF_8));

        try (Stream<Path> files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile)).containsExactly(root.resolve("file.txt"));
        }
    }

    @Test
    void rejectsKeysOutsideThePatternOrRoot() {
        assertThatThrownBy(() -> store.load("../escape.txt")).isInstanceOf(StorageException.class)
                .hasMessage("Invalid storage key");
        assertThatThrownBy(() -> store.write("file.pdf", new byte[0])).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> store.delete(root.resolve("x.txt").toString())).isInstanceOf(StorageException.class);
        assertThatThrownBy(() -> store.load(null)).isInstanceOf(StorageException.class);
    }

    @Test
    void missingFileFailsWithoutRevealingThePath() {
        assertThatThrownBy(() -> store.load("missing.txt")).isInstanceOf(StorageException.class)
                .hasMessageNotContaining(root.toString());
    }
}
