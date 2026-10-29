package com.investary.document.api;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Stable pagination envelope, independent of Spring Data's {@link Page} serialization.
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    static <E, T> PageResponse<T> from(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
