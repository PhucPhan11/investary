package com.investary.email.persistence;

import java.util.Collection;
import java.util.Locale;

import org.springframework.data.jpa.domain.Specification;

import com.investary.email.domain.SourceMessage;

public final class SourceMessageSpecifications {

    private SourceMessageSpecifications() {
    }

    public static Specification<SourceMessage> subjectContains(String text) {
        String pattern = "%" + escape(text.strip().toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("subject")), pattern, '\\');
    }

    public static Specification<SourceMessage> senderIs(String address) {
        String value = address.strip().toLowerCase(Locale.ROOT);
        return (root, query, cb) -> cb.equal(cb.lower(root.get("senderAddress")), value);
    }

    public static Specification<SourceMessage> idIn(Collection<java.util.UUID> ids) {
        return (root, query, cb) -> ids.isEmpty() ? cb.disjunction() : root.get("id").in(ids);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
