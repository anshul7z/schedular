package com.data.schedular.engine.mapping;

import java.util.List;
import java.util.Map;

/** Resolves dotted paths in documents. Numeric segments index into arrays. Missing values resolve to null. */
public final class DocumentPaths {

    private DocumentPaths() {
    }

    public static Object resolve(Object root, String[] path) {
        Object current = root;
        for (String segment : path) {
            current = switch (current) {
                case Map<?, ?> map -> map.get(segment);
                case List<?> list -> element(list, segment);
                case null, default -> null;
            };
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    public static Object resolve(Object root, String dottedPath) {
        return resolve(root, dottedPath.split("\\."));
    }

    private static Object element(List<?> list, String segment) {
        if (segment.isEmpty() || !segment.chars().allMatch(Character::isDigit) || segment.length() > 9) {
            return null;
        }
        int index = Integer.parseInt(segment);
        return index < list.size() ? list.get(index) : null;
    }
}
