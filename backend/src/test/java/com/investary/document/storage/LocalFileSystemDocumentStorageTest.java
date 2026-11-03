package com.investary.document.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.investary.common.storage.StorageException;
import com.investary.document.TestPdfs;

class LocalFileSystemDocumentStorageTest {

    @TempDir
    Path root;

    @Test
    void storesUnderGeneratedFlatKey() throws IOException {
        LocalFileSystemDocumentStorage storage = new LocalFileSystemDocumentStorage(new DocumentStorageProperties(root));
        byte[] pdf = TestPdfs.pdf("storage");

        String key = storage.store(new ByteArrayInputStream(pdf));

        assertThat(key).matches(LocalFileSystemDocumentStorage.KEY_PATTERN);
        assertThat(root.resolve(key)).exists();
        try (InputStream in = storage.load(key).getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(pdf);
        }
        assertThat(storage.delete(key)).isTrue();
        assertThat(root.resolve(key)).doesNotExist();
    }

    @Test
    void rejectsForeignKeys() {
        LocalFileSystemDocumentStorage storage = new LocalFileSystemDocumentStorage(new DocumentStorageProperties(root));

        assertThatThrownBy(() -> storage.load("../secret.pdf")).isInstanceOf(StorageException.class);
    }
}
