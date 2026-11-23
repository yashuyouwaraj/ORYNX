package com.orynx.orchestrator.common.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.Set;

/**
 * Enforces bounded page sizes, endpoint-specific sort-field allowlists, and
 * deterministic tie-breaker sorting across paginated API endpoints so unbounded
 * or arbitrary client requests cannot exhaust memory, trigger 500 errors, or
 * produce unstable page boundaries.
 */
public final class PaginationUtils {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    public static final Set<String> WORKFLOW_SORT_FIELDS = Set.of(
            "id", "name", "status", "startedAt", "completedAt", "scheduledAt"
    );

    public static final Set<String> TASK_SORT_FIELDS = Set.of(
            "executionOrder", "id", "status", "name"
    );

    public static final Set<String> EXECUTION_SORT_FIELDS = Set.of(
            "startedAt", "id", "status"
    );

    public static final Set<String> TIMELINE_SORT_FIELDS = Set.of(
            "timestamp", "id"
    );

    private PaginationUtils() {
    }

    public static Pageable normalize(Pageable pageable, Sort defaultSort) {
        return normalize(pageable, defaultSort, null);
    }

    public static Pageable normalize(
            Pageable pageable,
            Sort defaultSort,
            Set<String> allowedSortProperties
    ) {
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

        if (allowedSortProperties != null) {
            for (Sort.Order order : requestedSort) {
                if (!allowedSortProperties.contains(order.getProperty())) {
                    throw new IllegalArgumentException(
                            "Unsupported sort property: " + order.getProperty()
                    );
                }
            }
        }

        if (requestedSort.getOrderFor("id") == null) {
            requestedSort = requestedSort.and(Sort.by(Sort.Direction.DESC, "id"));
        }

        return PageRequest.of(pageNumber, pageSize, requestedSort);
    }
}
