package sqlancer.tidb.mixed;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import sqlancer.tidb.TiDBSchema.TiDBColumn;
import sqlancer.tidb.TiDBSchema.TiDBTable;

/** Actual catalog facts, plus the original DDL and explicit acquisition failures. No constraint solving. */
public final class TiDBMixedMetadata {
    public final JsonObject schema = new JsonObject();
    public final List<Map<String, Object>> evidence = new ArrayList<>();

    private TiDBMixedMetadata() {
    }

    private static String nullable(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    static String valueKind(String sqlType) {
        String type = sqlType.toLowerCase(Locale.ROOT);
        if (type.matches("^(tinyint|smallint|mediumint|int|integer|bigint|year)\\b.*")) {
            return "integer";
        }
        if (type.matches("^(decimal|numeric|float|double|real)\\b.*")) {
            return "decimal";
        }
        if (type.matches("^(.*blob|binary|varbinary|bit)\\b.*")) {
            return "binary";
        }
        return "text";
    }

    private static JsonObject column(Map<String, String> row) {
        JsonObject out = new JsonObject();
        String extra = row.getOrDefault("extra", "").toUpperCase(Locale.ROOT);
        String expression = nullable(row.get("generation_expression"));
        out.addProperty("name", row.get("column_name"));
        out.addProperty("sql_type", row.get("column_type"));
        out.addProperty("value_kind", valueKind(row.get("column_type")));
        out.addProperty("nullable", "YES".equals(row.get("is_nullable")));
        out.addProperty("generated",
                expression != null || extra.contains("VIRTUAL GENERATED") || extra.contains("STORED GENERATED"));
        out.addProperty("generation_expression", expression);
        out.addProperty("auto_increment", extra.contains("AUTO_INCREMENT"));
        // Catalog text, never executed. SHOW CREATE preserves DEFAULT NULL versus an absent default.
        out.addProperty("default_expression", row.get("column_default"));
        return out;
    }

    private static JsonObject constraint(String name, String kind) {
        JsonObject out = new JsonObject();
        out.addProperty("name", name);
        out.addProperty("kind", kind);
        out.add("columns", new JsonArray());
        out.add("expression", JsonNull.INSTANCE);
        out.addProperty("enforced", true);
        return out;
    }

    private static JsonArray uniqueKeys(List<Map<String, String>> rows) {
        Map<String, List<Map<String, String>>> indexes = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            if ("0".equals(row.get("non_unique"))) {
                indexes.computeIfAbsent(row.get("index_name"), key -> new ArrayList<>()).add(row);
            }
        }
        JsonArray constraints = new JsonArray();
        for (Map.Entry<String, List<Map<String, String>>> index : indexes.entrySet()) {
            JsonObject key = constraint(index.getKey(), "PRIMARY".equals(index.getKey()) ? "primary_key" : "unique");
            JsonArray parts = new JsonArray();
            index.getValue().sort(Comparator.comparingInt(row -> Integer.parseInt(row.get("seq_in_index"))));
            int sequence = 0;
            for (Map<String, String> row : index.getValue()) {
                if (Integer.parseInt(row.get("seq_in_index")) != ++sequence) {
                    throw new IllegalArgumentException("non-contiguous index sequence");
                }
                JsonObject part = new JsonObject();
                part.addProperty("sequence", sequence);
                part.addProperty("column", row.get("column_name"));
                part.addProperty("expression", nullable(row.get("expression")));
                part.addProperty("prefix_length", row.get("sub_part"));
                parts.add(part);
                if (row.get("column_name") != null) {
                    key.getAsJsonArray("columns").add(row.get("column_name"));
                }
            }
            key.add("key_parts", parts); // A prefix/expression key must not masquerade as a full-column key.
            constraints.add(key);
        }
        return constraints;
    }

    // Split SHOW CREATE at top-level commas, respecting quoted strings/identifiers and comments.
    static List<String> clauses(String ddl) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = -1;
        char quote = 0;
        boolean comment = false;
        for (int i = 0; i < ddl.length(); i++) {
            char ch = ddl.charAt(i);
            char next = i + 1 < ddl.length() ? ddl.charAt(i + 1) : 0;
            if (comment) {
                if (ch == '*' && next == '/') {
                    comment = false;
                    i++;
                }
            } else if (quote != 0) {
                if (ch == '\\' && quote != '`') {
                    i++;
                } else if (ch == quote && next == quote) {
                    i++;
                } else if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '\'' || ch == '"' || ch == '`') {
                quote = ch;
            } else if (ch == '/' && next == '*') {
                comment = true;
                i++;
            } else if (ch == '(') {
                if (depth++ == 0) {
                    start = i + 1;
                }
            } else if (ch == ')') {
                if (--depth == 0) {
                    result.add(ddl.substring(start, i).trim());
                    return result;
                }
            } else if (ch == ',' && depth == 1) {
                result.add(ddl.substring(start, i).trim());
                start = i + 1;
            }
        }
        throw new IllegalArgumentException("cannot parse SHOW CREATE table body");
    }

    private static JsonArray checks(List<Map<String, String>> rows, String ddl) {
        List<String> definitions = clauses(ddl);
        JsonArray result = new JsonArray();
        for (Map<String, String> row : rows) {
            String prefix = "CONSTRAINT `" + row.get("constraint_name").replace("`", "``") + "` CHECK ";
            String definition = definitions.stream().filter(part -> part.startsWith(prefix)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("CHECK missing from SHOW CREATE"));
            JsonObject check = constraint(row.get("constraint_name"), "check");
            check.addProperty("expression", row.get("check_clause"));
            check.addProperty("enforced",
                    !definition.endsWith("/*!80016 NOT ENFORCED */") && !definition.endsWith(" NOT ENFORCED"));
            result.add(check);
        }
        long ddlChecks = definitions.stream().filter(
                part -> part.startsWith("CONSTRAINT `") && part.matches("(?s)^CONSTRAINT `(?:[^`]|``)+` CHECK .*"))
                .count();
        if (ddlChecks != rows.size()) {
            throw new IllegalArgumentException("CHECK catalog and SHOW CREATE disagree");
        }
        return result;
    }

    private static List<Map<String, String>> read(TiDBMixedSupport io, String sql, Map<String, Object> audit,
            String key, List<String> errors) {
        try {
            List<Map<String, String>> rows = io.textRows(sql);
            audit.put(key, rows);
            return rows;
        } catch (SQLException | TiDBMixedSupport.Rejected error) {
            errors.add(key + ": " + error);
            return List.of();
        }
    }

    public static TiDBMixedMetadata collect(TiDBMixedSupport io, String database, List<TiDBTable> tables,
            long schemaRevision) {
        TiDBMixedMetadata result = new TiDBMixedMetadata();
        result.schema.addProperty("id", "schema_" + schemaRevision);
        JsonArray tableMetadata = new JsonArray();
        boolean complete = true;
        for (TiDBTable table : tables) {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("table", table.getName());
            List<String> errors = new ArrayList<>();
            audit.put("errors", errors);
            String filter = " WHERE TABLE_SCHEMA=" + TiDBMixedSupport.literal(database) + " AND TABLE_NAME="
                    + TiDBMixedSupport.literal(table.getName());
            List<Map<String, String>> columns = read(io,
                    "SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,GENERATION_EXPRESSION,"
                            + "NUMERIC_PRECISION,NUMERIC_SCALE,CHARACTER_MAXIMUM_LENGTH,COLLATION_NAME"
                            + " FROM information_schema.columns" + filter + " ORDER BY ORDINAL_POSITION",
                    audit, "columns", errors);
            List<Map<String, String>> indexes = read(io,
                    "SELECT INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,SUB_PART,EXPRESSION"
                            + " FROM information_schema.statistics" + filter + " ORDER BY INDEX_NAME,SEQ_IN_INDEX",
                    audit, "indexes", errors);
            List<Map<String, String>> checkRows = read(io,
                    "SELECT CONSTRAINT_NAME,CHECK_CLAUSE FROM information_schema.tidb_check_constraints"
                            + " WHERE CONSTRAINT_SCHEMA=" + TiDBMixedSupport.literal(database) + " AND TABLE_NAME="
                            + TiDBMixedSupport.literal(table.getName()),
                    audit, "checks", errors);
            List<Map<String, String>> creates = read(io,
                    "SHOW CREATE TABLE " + TiDBMixedSupport.quoteIdentifier(table.getName()), audit, "create", errors);
            JsonObject metadata = new JsonObject();
            metadata.addProperty("name", table.getName());
            JsonArray cols = new JsonArray();
            if (columns.isEmpty()) {
                errors.add("no actual column metadata");
                for (TiDBColumn col : table.getColumns()) {
                    Map<String, String> fallback = new LinkedHashMap<>();
                    fallback.put("column_name", col.getName());
                    fallback.put("column_type", col.getSqlType());
                    fallback.put("is_nullable", col.isNullable() ? "YES" : "NO");
                    fallback.put("column_default", col.getDefaultValue());
                    fallback.put("extra", col.getExtra());
                    cols.add(column(fallback));
                }
            } else {
                columns.forEach(row -> cols.add(column(row)));
            }
            JsonArray constraints = new JsonArray();
            try {
                constraints = uniqueKeys(indexes);
                if (!creates.isEmpty()) {
                    String ddl = creates.get(0).get("create table");
                    if (ddl == null) {
                        throw new IllegalArgumentException("missing table DDL");
                    }
                    constraints.addAll(checks(checkRows, ddl));
                    if (clauses(ddl).stream().anyMatch(part -> part.contains(" FOREIGN KEY "))) {
                        errors.add("foreign-key semantics not represented; see original DDL");
                    }
                } else {
                    errors.add("no SHOW CREATE evidence");
                }
            } catch (IllegalArgumentException error) {
                errors.add(error.toString());
            }
            metadata.add("columns", cols);
            metadata.add("constraints", constraints);
            metadata.addProperty("metadata_complete", errors.isEmpty());
            complete &= errors.isEmpty();
            tableMetadata.add(metadata);
            result.evidence.add(audit);
        }
        result.schema.add("tables", tableMetadata);
        result.schema.addProperty("complete", complete);
        return result;
    }
}
