package com.investary.email;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * @param maxSize largest accepted EML file
 */
@Validated
@ConfigurationProperties("investary.email")
public record EmailProperties(@Valid @NotNull Storage storage, @NotNull DataSize maxSize) {

    /**
     * @param root directory for original EML files; must be outside the source tree
     */
    public record Storage(@NotNull Path root) {
    }
}
