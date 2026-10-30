package com.investary.document.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.investary.document.TestPdfs;

class PdfSignatureValidatorTest {

    @Test
    void acceptsPdfSignatureAtStart() throws IOException {
        assertThat(PdfSignatureValidator.isPdf(new ByteArrayInputStream(TestPdfs.pdf("a")))).isTrue();
    }

    @Test
    void acceptsPdfSignatureAfterLeadingJunk() throws IOException {
        byte[] content = ("﻿junk" + new String(TestPdfs.pdf("a"), StandardCharsets.US_ASCII)).getBytes(StandardCharsets.UTF_8);
        assertThat(PdfSignatureValidator.isPdf(new ByteArrayInputStream(content))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "%PDF", "hello world", "<html>%PD F-</html>"})
    void rejectsContentWithoutSignature(String content) throws IOException {
        assertThat(PdfSignatureValidator.isPdf(new ByteArrayInputStream(content.getBytes(StandardCharsets.US_ASCII)))).isFalse();
    }

    @Test
    void rejectsSignatureBeyondSearchWindow() throws IOException {
        byte[] content = ("x".repeat(2048) + "%PDF-1.7").getBytes(StandardCharsets.US_ASCII);
        assertThat(PdfSignatureValidator.isPdf(new ByteArrayInputStream(content))).isFalse();
    }
}
