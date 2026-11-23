package com.orynx.orchestrator.common.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Enforces bounded page sizes and deterministic tie-breaker sorting across
 * paginated API endpoints so unbounded client requests cannot exhaust memory or
 * produce unstable page boundaries.
 */
public final class PaginationUtils {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private PaginationUtils() {
    }

    public static Pageable normalize(Pageable pageable, Sort defaultSort) {
        Sort effectiveDefault = (defaultSort != null && defaultSort.isSorted())
                ? defaultSort
                : Sort.by(Sort.Direction.DESC, "id");

        if (pageable == null || pageable.isUnpaged()) {
            return PageRequest.of(0, DEFAULT_PAGE_SIZE, effectiveDefault);
        }

        int pageNumber = Math.max(0, pageable.getPageNumber());
        int pageSize = Math.min(Math.max(1, pageable.getPageSize()), MAX_PAGE_SIZE);

        Sort requestedSort = pageable.getSort();
        if (requestedSort.isUnsorted()) {
            return PageRequest.of(pageNumber, pageSize, effectiveDefault);
        }

        if (requestedSort.getOrderFor("id") == null) {
            requestedSort = requestedSort.and(Sort.by(Sort.Direction.DESC, "id"));
        }

        return PageRequest.of(pageNumber, pageSize, requestedSort);
    }
}
