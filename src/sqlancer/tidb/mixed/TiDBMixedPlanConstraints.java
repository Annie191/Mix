package sqlancer.tidb.mixed;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Positive, witnessed constraint failures only. Unknown values and absent witnesses are never proof of feasibility. */
final class TiDBMixedPlanConstraints {
    private TiDBMixedPlanConstraints() {
    }

    private static void reject(String code, String path, String message, JsonObject proof) {
        JsonObject details = new JsonObject();
        details.addProperty("path", path);
        details.addProperty("expected", "values satisfying actual column and complete unique-key constraints");
        details.add("constraint_witness", proof);
        throw new TiDBDataPreparationProtocol.ValidationException(code, message, details);
    }

    private static String equalValue(JsonObject column, JsonElement value) {
        if (value == null || column.get("generated").getAsBoolean()) {
            return null;
        }
        JsonObject cell = value.getAsJsonObject();
        String kind = cell.get("type").getAsString();
        if (kind.equals("null")) {
            return null; // NULLs in nullable UNIQUE keys do not conflict.
        }
        String actualKind = column.get("value_kind").getAsString();
        if (List.of("integer", "decimal").contains(actualKind) && List.of("integer", "decimal").contains(kind)) {
            return "number:" + new BigDecimal(cell.get("value").getAsString()).stripTrailingZeros().toPlainString();
        }
        return actualKind.equals(kind) ? kind + ":" + cell.get("value").getAsString() : null;
    }

    private static void range(JsonObject column, JsonElement value, String path) {
        if (value == null || !value.getAsJsonObject().get("type").getAsString().equals("integer")) {
            return;
        }
        String type = column.get("sql_type").getAsString().toLowerCase(Locale.ROOT);
        Matcher match = Pattern
                .compile(
                        "^(tinyint|smallint|mediumint|int|integer|bigint)(?:\\([^)]*\\))?(?: unsigned)?(?: zerofill)?$")
                .matcher(type);
        if (!match.matches()) {
            return;
        }
        Map<String, Integer> widths = Map.of("tinyint", 8, "smallint", 16, "mediumint", 24, "int", 32, "integer", 32,
                "bigint", 64);
        boolean unsigned = type.contains("unsigned") || type.contains("zerofill");
        int bits = widths.get(match.group(1));
        BigInteger minimum = unsigned ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(bits - 1).negate();
        BigInteger maximum = BigInteger.ONE.shiftLeft(unsigned ? bits : bits - 1).subtract(BigInteger.ONE);
        BigInteger proposed = new BigInteger(value.getAsJsonObject().get("value").getAsString());
        if (proposed.compareTo(minimum) < 0 || proposed.compareTo(maximum) > 0) {
            JsonObject proof = new JsonObject();
            proof.addProperty("sql_type", type);
            proof.addProperty("minimum", minimum.toString());
            proof.addProperty("maximum", maximum.toString());
            proof.add("received", value.deepCopy());
            reject("ROW_VALUE_OUT_OF_RANGE", path, "Explicit integer is outside the actual SQL column range", proof);
        }
    }

    private static List<JsonObject> witnessedRows(JsonObject state, String table) {
        List<JsonObject> result = new ArrayList<>();
        for (JsonElement entry : state.getAsJsonArray("samples")) {
            JsonObject sample = entry.getAsJsonObject();
            if (!sample.get("table").getAsString().equals(table)
                    || !List.of("query_id", "schema_revision", "data_revision").stream()
                            .allMatch(key -> state.get(key).equals(sample.get(key)))) {
                continue;
            }
            for (JsonElement row : sample.getAsJsonArray("rows")) {
                JsonObject values = new JsonObject();
                for (int i = 0; i < sample.getAsJsonArray("columns").size(); i++) {
                    values.add(sample.getAsJsonArray("columns").get(i).getAsString(), row.getAsJsonArray().get(i));
                }
                result.add(values);
            }
        }
        return result;
    }

    private static String tuple(JsonObject key, Map<String, JsonObject> columns, JsonObject row) {
        if (!key.get("enforced").getAsBoolean()
                || !List.of("primary_key", "unique").contains(key.get("kind").getAsString()) || !key.has("key_parts")
                || key.getAsJsonArray("key_parts").isEmpty()) {
            return null;
        }
        com.google.gson.JsonArray tuple = new com.google.gson.JsonArray();
        for (JsonElement entry : key.getAsJsonArray("key_parts")) {
            JsonObject part = entry.getAsJsonObject();
            if (part.get("column").isJsonNull() || !part.get("expression").isJsonNull()
                    || !part.get("prefix_length").isJsonNull()) {
                return null;
            }
            String name = part.get("column").getAsString();
            if (!columns.containsKey(name)) {
                return null;
            }
            String value = equalValue(columns.get(name), row.get(name));
            if (value == null) {
                return null;
            }
            tuple.add(value);
        }
        return tuple.toString();
    }

    static void validate(JsonObject plan, JsonObject state) {
        if (!plan.has("operations") || !state.getAsJsonObject("schema").get("complete").getAsBoolean()) {
            return;
        }
        for (JsonElement entry : state.getAsJsonObject("schema").getAsJsonArray("tables")) {
            JsonObject table = entry.getAsJsonObject();
            if (!table.get("metadata_complete").getAsBoolean()) {
                continue;
            }
            String name = table.get("name").getAsString();
            Map<String, JsonObject> columns = new LinkedHashMap<>();
            table.getAsJsonArray("columns")
                    .forEach(c -> columns.put(c.getAsJsonObject().get("name").getAsString(), c.getAsJsonObject()));
            List<JsonObject> rows = witnessedRows(state, name);
            for (int op = 0; op < plan.getAsJsonArray("operations").size(); op++) {
                JsonObject operation = plan.getAsJsonArray("operations").get(op).getAsJsonObject();
                if (!operation.get("table").getAsString().equals(name)) {
                    continue;
                }
                for (int index = 0; index < operation.getAsJsonArray("rows").size(); index++) {
                    JsonObject proposed = operation.getAsJsonArray("rows").get(index).getAsJsonObject();
                    String path = "$.operations[" + op + "].rows[" + index + "]";
                    columns.forEach((column, spec) -> range(spec, proposed.get(column), path + "." + column));
                    for (JsonElement constraint : table.getAsJsonArray("constraints")) {
                        JsonObject key = constraint.getAsJsonObject();
                        String value = tuple(key, columns, proposed);
                        if (value != null && rows.stream().anyMatch(row -> value.equals(tuple(key, columns, row)))) {
                            JsonObject proof = new JsonObject();
                            proof.addProperty("table", name);
                            proof.add("constraint", key.deepCopy());
                            proof.addProperty("key_tuple", value);
                            proof.addProperty("evidence", "current sampled row or earlier row in this plan");
                            reject("UNIQUE_KEY_CONFLICT", path,
                                    "Complete unique key already exists in witnessed data or this plan", proof);
                        }
                    }
                    rows.add(proposed);
                }
            }
        }
    }
}
