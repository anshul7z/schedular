package com.data.schedular.engine.mapping;

import com.data.schedular.engine.mapping.MappingPlan.ChildPlan;
import com.data.schedular.engine.mapping.MappingPlan.ColumnBinding;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Turns one source document into a parent row plus child-table rows, following a {@link MappingPlan}. */
public final class MappingEngine {

    private MappingEngine() {
    }

    /**
     * Rows produced from one document.
     *
     * @param key       the coerced primary-key value (also {@code parent_id} of the child rows)
     * @param parentRow values in {@code plan.table()} column order
     * @param childRows one list per {@code plan.children()} entry, in the same order
     */
    public record MappedDocument(Object key, Object[] parentRow, List<List<Object[]>> childRows) {
    }

    public static MappedDocument map(MappingPlan plan, Map<String, Object> document) {
        ColumnBinding pk = plan.primaryKey();
        Object key = TypeCoercer.coerce(DocumentPaths.resolve(document, pk.path()), pk.column());
        if (key == null) {
            throw new DocumentMappingException("document has no value at primary key path '"
                    + String.join(".", pk.path()) + "'");
        }

        Object[] parent = new Object[plan.table().columns().size()];
        int i = 0;
        parent[i++] = key;
        for (ColumnBinding binding : plan.columns()) {
            parent[i++] = TypeCoercer.coerce(DocumentPaths.resolve(document, binding.path()), binding.column());
        }
        if (plan.overflow()) {
            parent[i] = overflow(plan, document);
        }

        List<List<Object[]>> children = new ArrayList<>(plan.children().size());
        for (ChildPlan child : plan.children()) {
            children.add(childRows(child, key, DocumentPaths.resolve(document, child.arrayPath())));
        }
        return new MappedDocument(key, parent, children);
    }

    private static List<Object[]> childRows(ChildPlan child, Object key, Object array) {
        if (array == null) {
            return List.of();
        }
        if (!(array instanceof List<?> elements)) {
            throw new DocumentMappingException("expected an array at '" + String.join(".", child.arrayPath())
                    + "' for child table '" + child.table().name() + "', found "
                    + array.getClass().getSimpleName());
        }
        List<Object[]> rows = new ArrayList<>(elements.size());
        for (int ordinal = 0; ordinal < elements.size(); ordinal++) {
            Object element = elements.get(ordinal);
            Object source = element instanceof Map<?, ?>
                    ? element
                    : Collections.singletonMap(MappingPlan.SCALAR_ELEMENT_FIELD, element);
            Object[] row = new Object[child.table().columns().size()];
            row[0] = key;
            row[1] = ordinal;
            int i = 2;
            for (ColumnBinding binding : child.columns()) {
                row[i++] = TypeCoercer.coerce(DocumentPaths.resolve(source, binding.path()), binding.column());
            }
            rows.add(row);
        }
        return rows;
    }

    private static String overflow(MappingPlan plan, Map<String, Object> document) {
        Map<String, Object> extra = new LinkedHashMap<>();
        document.forEach((k, v) -> {
            if (!plan.mappedTopLevel().contains(k)) {
                extra.put(k, v);
            }
        });
        return extra.isEmpty() ? null : BsonValues.toJson(extra);
    }
}
