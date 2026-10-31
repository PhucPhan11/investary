package com.investary.document.storage;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotNull;

/**
 * @param root directory for original PDF files; must be outside the source tree
 */
@Validated
@ConfigurationProperties("investary.storage")
public record DocumentStorageProperties(@NotNull Path root) {
}
