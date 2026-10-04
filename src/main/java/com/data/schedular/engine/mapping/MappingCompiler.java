package com.data.schedular.engine.mapping;

import com.data.schedular.engine.mapping.MappingPlan.ChildPlan;
import com.data.schedular.engine.mapping.MappingPlan.ColumnBinding;
import com.data.schedular.engine.mapping.MappingSpec.FieldSpec;
import com.data.schedular.engine.mapping.MappingSpec.PrimaryKeySpec;
import com.data.schedular.engine.mapping.MappingSpec.UnmappedFields;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Parses and validates a {@link MappingSpec} and compiles it into a {@link MappingPlan}. */
public final class MappingCompiler {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,62}");
    private static final Set<LogicalType> KEY_TYPES = Set.of(LogicalType.STRING, LogicalType.INT, LogicalType.LONG);
    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .build();

    private MappingCompiler() {
    }

    public static MappingSpec parse(String mappingJson) {
        if (mappingJson == null || mappingJson.isBlank()) {
            return new MappingSpec(null, List.of(), null);
        }
        try {
            return MAPPER.readValue(mappingJson, MappingSpec.class);
        } catch (JacksonException e) {
            throw new InvalidMappingException("Mapping JSON is invalid: " + e.getOriginalMessage(), e);
        }
    }

    public static MappingPlan compile(String targetTable, String mappingJson) {
        return compile(targetTable, parse(mappingJson));
    }

    public static MappingPlan compile(String targetTable, MappingSpec spec) {
        checkTableName(targetTable);

        PrimaryKeySpec pkSpec = spec.primaryKey() != null
                ? spec.primaryKey()
                : new PrimaryKeySpec(null, null, null, null);
        String pkSource = pkSpec.source() != null ? pkSpec.source() : "_id";
        String pkColumn = pkSpec.column() != null ? pkSpec.column() : "id";
        LogicalType pkType = pkSpec.type() != null ? pkSpec.type() : LogicalType.STRING;
        if (!KEY_TYPES.contains(pkType)) {
            throw new InvalidMappingException("primaryKey.type must be one of " + KEY_TYPES + ", got " + pkType);
        }
        checkIdentifier(pkColumn, "primaryKey.column");
        ColumnDef pkDef = new ColumnDef(pkColumn, pkType, pkSpec.length(), false);
        ColumnBinding pk = new ColumnBinding(splitPath(pkSource, "primaryKey.source"), pkDef);

        Set<String> parentColumns = new HashSet<>();
        parentColumns.add(pkColumn.toLowerCase(Locale.ROOT));
        Set<String> childTables = new HashSet<>();
        Set<String> mappedTopLevel = new LinkedHashSet<>();
        mappedTopLevel.add(pk.path()[0]);

        List<ColumnBinding> columns = new ArrayList<>();
        List<ChildPlan> children = new ArrayList<>();
        for (FieldSpec field : spec.fields() == null ? List.<FieldSpec>of() : spec.fields()) {
            NestedStrategy strategy = field.strategy() != null ? field.strategy() : NestedStrategy.FLATTEN;
            String[] path = splitPath(field.path(), "field path");
            mappedTopLevel.add(path[0]);
            if (strategy == NestedStrategy.CHILD_TABLE) {
                children.add(compileChild(field, path, pkDef, targetTable, childTables));
            } else {
                ColumnBinding binding = compileColumn(field, path, strategy);
                addColumn(parentColumns, binding.column().name(), targetTable);
                columns.add(binding);
            }
        }

        boolean overflow = spec.unmappedFields() == UnmappedFields.JSON_OVERFLOW_COLUMN;
        List<ColumnDef> tableColumns = new ArrayList<>();
        tableColumns.add(pkDef);
        columns.forEach(b -> tableColumns.add(b.column()));
        if (overflow) {
            addColumn(parentColumns, MappingPlan.OVERFLOW_COLUMN, targetTable);
            tableColumns.add(new ColumnDef(MappingPlan.OVERFLOW_COLUMN, LogicalType.JSON, null, true));
        }
        TableDef table = new TableDef(targetTable, tableColumns, List.of(pkColumn));
        return new MappingPlan(table, pk, List.copyOf(columns), List.copyOf(children), overflow,
                Set.copyOf(mappedTopLevel));
    }

    private static ChildPlan compileChild(FieldSpec field, String[] arrayPath, ColumnDef pkDef, String parentTable,
                                          Set<String> childTables) {
        String table = field.childTable();
        if (table == null) {
            throw new InvalidMappingException("Field '" + field.path() + "' uses CHILD_TABLE but has no childTable");
        }
        checkTableName(table);
        if (table.equalsIgnoreCase(parentTable) || !childTables.add(table.toLowerCase(Locale.ROOT))) {
            throw new InvalidMappingException("Child table '" + table + "' is used more than once");
        }

        List<FieldSpec> elementFields = field.fields() == null || field.fields().isEmpty()
                ? List.of(new FieldSpec(MappingPlan.SCALAR_ELEMENT_FIELD, null, field.type(), field.length(), null,
                        null, null))
                : field.fields();
        Set<String> names = new HashSet<>(Set.of(MappingPlan.PARENT_ID_COLUMN, MappingPlan.ORDINAL_COLUMN));
        List<ColumnBinding> bindings = new ArrayList<>();
        for (FieldSpec element : elementFields) {
            NestedStrategy strategy = element.strategy() != null ? element.strategy() : NestedStrategy.FLATTEN;
            if (strategy == NestedStrategy.CHILD_TABLE) {
                throw new InvalidMappingException("Nested CHILD_TABLE inside '" + table + "' is not supported; "
                        + "use strategy JSON for '" + element.path() + "'");
            }
            ColumnBinding binding = compileColumn(element, splitPath(element.path(), "field path"), strategy);
            addColumn(names, binding.column().name(), table);
            bindings.add(binding);
        }

        List<ColumnDef> columns = new ArrayList<>();
        columns.add(new ColumnDef(MappingPlan.PARENT_ID_COLUMN, pkDef.type(), pkDef.length(), false));
        columns.add(new ColumnDef(MappingPlan.ORDINAL_COLUMN, LogicalType.INT, null, false));
        bindings.forEach(b -> columns.add(b.column()));
        TableDef tableDef = new TableDef(table, columns,
                List.of(MappingPlan.PARENT_ID_COLUMN, MappingPlan.ORDINAL_COLUMN));
        return new ChildPlan(tableDef, arrayPath, List.copyOf(bindings));
    }

    private static ColumnBinding compileColumn(FieldSpec field, String[] path, NestedStrategy strategy) {
        String column = field.column() != null ? field.column() : String.join("_", path);
        checkIdentifier(column, "column for '" + field.path() + "'");
        LogicalType type;
        if (strategy == NestedStrategy.JSON) {
            if (field.type() != null && field.type() != LogicalType.JSON) {
                throw new InvalidMappingException("Field '" + field.path() + "' uses strategy JSON; "
                        + "remove type " + field.type());
            }
            type = LogicalType.JSON;
        } else {
            type = field.type() != null ? field.type() : LogicalType.STRING;
        }
        if (field.length() != null && (field.length() < 1 || field.length() > 10_485_760)) {
            throw new InvalidMappingException("length for '" + field.path() + "' must be between 1 and 10485760");
        }
        return new ColumnBinding(path, new ColumnDef(column, type, field.length(), true));
    }

    private static void addColumn(Set<String> names, String column, String table) {
        if (!names.add(column.toLowerCase(Locale.ROOT))) {
            throw new InvalidMappingException("Column '" + column + "' appears more than once in table '"
                    + table + "'");
        }
    }

    private static String[] splitPath(String path, String what) {
        if (path == null || path.isBlank()) {
            throw new InvalidMappingException(what + " is required");
        }
        String[] parts = path.split("\\.", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new InvalidMappingException("Invalid " + what + " '" + path + "'");
            }
        }
        return parts;
    }

    private static void checkTableName(String table) {
        if (table == null || table.isBlank()) {
            throw new InvalidMappingException("Target table name is required");
        }
        String[] parts = table.split("\\.", -1);
        if (parts.length > 2) {
            throw new InvalidMappingException("Table name '" + table + "' must be 'table' or 'schema.table'");
        }
        for (String part : parts) {
            checkIdentifier(part, "table name '" + table + "'");
        }
    }

    private static void checkIdentifier(String name, String what) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw new InvalidMappingException("Invalid " + what + ": '" + name
                    + "' (use letters, digits and underscores, starting with a letter or underscore, max 63)");
        }
    }
}
