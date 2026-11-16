package com.seatlock.inventory.api;

import org.springframework.data.domain.Page;

import java.util.List;

/** A stable public API representation independent of Spring Data's PageImpl JSON shape. */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean hasNext
) {
    public static <T> PageResponse<T> from(Page<T> result) {
        return new PageResponse<>(
                result.getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }
}
