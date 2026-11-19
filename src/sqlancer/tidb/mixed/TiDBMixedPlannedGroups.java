package sqlancer.tidb.mixed;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** An upper bound, never a success prediction: coarsen grouping and ignore ON/WHERE/HAVING constraints. */
final class TiDBMixedPlannedGroups {
    private TiDBMixedPlannedGroups() {
    }

    private static boolean current(JsonObject fact, JsonObject state) {
        return List.of("query_id", "schema_revision", "data_revision").stream()
                .allMatch(key -> state.get(key).equals(fact.get(key)));
    }

    private static List<JsonObject> existing(JsonObject state, String table) {
        for (JsonElement entry : state.getAsJsonArray("samples")) {
            JsonObject sample = entry.getAsJsonObject();
            if (!table.equals(sample.get("table").getAsString()) || !current(sample, state)
                    || !sample.get("complete").getAsBoolean()) {
                continue;
            }
            List<JsonObject> rows = new ArrayList<>();
            for (JsonElement row : sample.getAsJsonArray("rows")) {
                JsonObject values = new JsonObject();
                for (int i = 0; i < sample.getAsJsonArray("columns").size(); i++) {
                    values.add(sample.getAsJsonArray("columns").get(i).getAsString(), row.getAsJsonArray().get(i));
                }
                rows.add(values);
            }
            return rows;
        }
        JsonObject count = TiDBMixedObservationRequest.currentExact(state,
                new com.google.gson.JsonPrimitive("rows_" + table));
        if (count != null && new BigInteger(count.getAsJsonObject("value").get("count").getAsString()).signum() == 0) {
            return new ArrayList<>();
        }
        return null;
    }

    private static String numeric(JsonObject column, JsonElement value) {
        if (value == null || !value.isJsonObject() || column.get("generated").getAsBoolean()) {
            return null;
        }
        JsonObject cell = value.getAsJsonObject();
        String kind = cell.get("type").getAsString();
        if (kind.equals("null")) {
            return "NULL";
        }
        if (!List.of("integer", "decimal").contains(kind)) {
            return null;
        }
        String sqlType = column.get("sql_type").getAsString().toLowerCase(Locale.ROOT);
        BigDecimal number = new BigDecimal(cell.get("value").getAsString());
        if (sqlType.matches("(tinyint|smallint|mediumint|int|integer|bigint)(\\b|\\().*")) {
            try {
                return number.toBigIntegerExact().toString();
            } catch (ArithmeticException fractional) {
                return null; // Unknown storage conversion: merge this discriminator rather than prove a false bound.
            }
        }
        if ((sqlType.startsWith("double") || sqlType.startsWith("float")) && !sqlType.contains("(")) {
            double converted = sqlType.startsWith("float") ? (double) number.floatValue() : number.doubleValue();
            return Double.isFinite(converted) ? Double.toString(converted == 0 ? 0 : converted) : null;
        }
        // Text collations, DECIMAL rounding and expression/generated keys need database validation.
        return null;
    }

    static JsonObject analyze(JsonObject plan, JsonObject state) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "unverified");
        result.add("upper_bound", JsonNull.INSTANCE);
        result.addProperty("meaning",
                "Upper bound for all existing plus proposed rows; not proof of successful inserts or target achievement");
        JsonArray factors = new JsonArray();
        result.add("table_factors", factors);
        JsonArray grouping = state.getAsJsonObject("query").getAsJsonArray("group_by");
        if (grouping.isEmpty() || !plan.has("operations")
                || !state.getAsJsonObject("schema").get("complete").getAsBoolean()) {
            result.addProperty("reason", "No explicit grouping, plan or complete metadata");
            return result;
        }
        boolean outer = false;
        for (JsonElement join : state.getAsJsonObject("query").getAsJsonArray("joins")) {
            String type = join.getAsJsonObject().get("type").getAsString();
            if (!List.of("INNER", "CROSS", "STRAIGHT", "NATURAL_INNER", "LEFT", "RIGHT").contains(type)) {
                result.addProperty("reason", "Unsupported join kind");
                return result;
            }
            outer |= type.equals("LEFT") || type.equals("RIGHT");
        }
        BigInteger product = BigInteger.ONE;
        for (JsonElement entry : state.getAsJsonObject("schema").getAsJsonArray("tables")) {
            JsonObject table = entry.getAsJsonObject();
            String name = table.get("name").getAsString();
            List<JsonObject> rows = existing(state, name);
            if (rows == null || !table.get("metadata_complete").getAsBoolean()) {
                result.addProperty("reason",
                        "Need a current complete sample (or exact zero rows) and metadata for " + name);
                return result;
            }
            for (JsonElement operation : plan.getAsJsonArray("operations")) {
                if (name.equals(operation.getAsJsonObject().get("table").getAsString())) {
                    operation.getAsJsonObject().getAsJsonArray("rows").forEach(row -> rows.add(row.getAsJsonObject()));
                }
            }
            List<JsonObject> discriminators = new ArrayList<>();
            for (JsonElement col : table.getAsJsonArray("columns")) {
                JsonObject column = col.getAsJsonObject();
                String columnName = column.get("name").getAsString();
                boolean grouped = grouping.asList().stream()
                        .anyMatch(key -> key.getAsString().equals(name + "." + columnName));
                if (grouped && rows.stream().allMatch(row -> numeric(column, row.get(columnName)) != null)) {
                    discriminators.add(column);
                }
            }
            Map<List<String>, Long> frequencies = new LinkedHashMap<>();
            for (JsonObject row : rows) {
                List<String> key = new ArrayList<>();
                discriminators.forEach(column -> key.add(numeric(column, row.get(column.get("name").getAsString()))));
                frequencies.merge(key, 1L, Long::sum);
            }
            long largest = frequencies.values().stream().mapToLong(Long::longValue).max().orElse(0);
            boolean nonNullKey = discriminators.stream().anyMatch(column -> !column.get("nullable").getAsBoolean());
            // Add a possible NULL-extended tuple. A NOT NULL grouped discriminator keeps it in a separate bucket.
            long factor = outer ? nonNullKey ? Math.max(1, largest) : largest + 1 : largest;
            product = product.multiply(BigInteger.valueOf(factor));
            JsonObject proof = new JsonObject();
            proof.addProperty("table", name);
            proof.addProperty("existing_and_proposed_rows", rows.size());
            proof.addProperty("factor", factor);
            JsonArray keys = new JsonArray();
            discriminators.forEach(column -> keys.add(column.get("name")));
            proof.add("numeric_group_discriminators", keys);
            factors.add(proof);
        }
        result.addProperty("status", "proved_upper_bound");
        result.addProperty("upper_bound", product.toString());
        result.addProperty("reason", "Full samples plus proposed values; ignored expressions/types coarsen groups, "
                + "ON/WHERE/HAVING and uniqueness can only reduce this bound; outer NULL extension is included");
        return result;
    }
}
