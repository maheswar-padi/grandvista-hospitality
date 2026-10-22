package com.grandvista.platform.persistence;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds dynamic JPA filters where an absent value simply adds no predicate. This replaces JPQL of the
 * form {@code (:param IS NULL OR column = :param)}, which PostgreSQL rejects when a null parameter's
 * type cannot be inferred (dates, UUIDs).
 */
public final class Specs {

    private Specs() {
    }

    @SafeVarargs
    public static <T> Specification<T> allOf(Specification<T>... specs) {
        return Specification.allOf(Arrays.stream(specs).filter(Objects::nonNull).toList());
    }

    /** {@code attribute = value}, or no predicate when value is null. */
    public static <T> Specification<T> eq(String attribute, Object value) {
        return value == null ? null : (root, query, cb) -> cb.equal(root.get(attribute), value);
    }

    /** Case-insensitive "contains" across the given string attributes, or no predicate for blank text. */
    public static <T> Specification<T> containsIgnoreCase(String text, String... attributes) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String pattern = "%" + text.trim().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
        return (root, query, cb) -> cb.or(Arrays.stream(attributes)
                .map(a -> cb.like(cb.lower(root.get(a)), pattern))
                .toArray(jakarta.persistence.criteria.Predicate[]::new));
    }
}
