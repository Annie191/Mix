package sqlancer.tidb.mixed;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Exact magnitudes and logarithmic buckets, separate from first-time boolean scene hits. */
final class TiDBMixedDataScale {
    private static final List<String> GROUP_FIELDS = List.of("group_count", "input_rows", "min_size", "max_size");

    private TiDBMixedDataScale() {
    }

    static String bucket(BigInteger count) {
        if (count.compareTo(BigInteger.ONE) <= 0) {
            return count.toString();
        }
        BigInteger lower = BigInteger.ONE.shiftLeft(count.bitLength() - 1);
        return lower + "-" + lower.shiftLeft(1).subtract(BigInteger.ONE);
    }

    private static String scope(String id) {
        return List.of("query_input_before", "query_input_after", "groups_before_having", "groups_after_having")
                .contains(id) ? "query_stages" : TiDBMixedDataCoverage.scope(id);
    }

    private static void metric(JsonObject metrics, String id, JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return;
        }
        try {
            BigInteger count = new BigDecimal(value.getAsString()).toBigIntegerExact();
            if (count.signum() >= 0) {
                JsonObject measurement = new JsonObject();
                measurement.addProperty("count", count.toString());
                measurement.addProperty("bucket", bucket(count));
                metrics.add(id, measurement);
            }
        } catch (IllegalArgumentException | ArithmeticException invalid) {
            // A missing/nonintegral scalar is not an exact count. Leave the expected metric unverified.
        }
    }

    private static void summary(JsonObject metrics, String id, JsonObject value) {
        if (!value.has("complete") || !value.get("complete").getAsBoolean()
                || value.getAsJsonArray("rows").size() != 1) {
            return;
        }
        JsonArray columns = value.getAsJsonArray("columns");
        JsonArray row = value.getAsJsonArray("rows").get(0).getAsJsonArray();
        for (int i = 0; i < columns.size(); i++) {
            String field = columns.get(i).getAsString();
            if (GROUP_FIELDS.contains(field)) {
                metric(metrics, id + "." + field, row.get(i).getAsJsonObject().get("value"));
            }
        }
    }

    static JsonObject snapshot(JsonObject packet, Map<String, JsonObject> facts) {
        JsonObject result = new JsonObject();
        result.addProperty("version", 1);
        for (String key : List.of("query_id", "schema_revision", "data_revision")) {
            result.add(key, packet.get(key).deepCopy());
        }
        for (String scope : List.of("base_data", "query_stages")) {
            Set<String> expected = new TreeSet<>();
            JsonObject metrics = new JsonObject();
            for (JsonElement entry : packet.getAsJsonArray("available_observations")) {
                JsonObject capability = entry.getAsJsonObject();
                String id = capability.get("id").getAsString();
                if (capability.has("parameters") || !scope.equals(scope(id))) {
                    continue;
                }
                JsonObject value = facts.get(id);
                if (id.startsWith("groups_")) {
                    GROUP_FIELDS.forEach(field -> expected.add(id + "." + field));
                    if (value != null) {
                        summary(metrics, id, value);
                    }
                } else {
                    expected.add(id + ".count");
                    if (value != null && value.has("count")) {
                        metric(metrics, id + ".count", value.get("count"));
                    }
                }
            }
            Set<String> unverified = new TreeSet<>(expected);
            unverified.removeAll(metrics.keySet());
            JsonObject scoped = new JsonObject();
            scoped.add("metrics", metrics);
            scoped.add("expected", new com.google.gson.Gson().toJsonTree(expected));
            scoped.add("unverified", new com.google.gson.Gson().toJsonTree(unverified));
            result.add(scope, scoped);
        }
        return result;
    }

    private static Set<String> strings(JsonArray values) {
        Set<String> result = new TreeSet<>();
        values.forEach(value -> result.add(value.getAsString()));
        return result;
    }

    static JsonObject difference(JsonObject before, JsonObject after) {
        JsonObject result = new JsonObject();
        result.addProperty("version", 1);
        boolean sameQuery = List.of("query_id", "schema_revision").stream()
                .allMatch(key -> before.get(key).equals(after.get(key)));
        result.addProperty("same_query_and_schema", sameQuery);
        for (String scope : List.of("base_data", "query_stages")) {
            JsonObject left = before.getAsJsonObject(scope);
            JsonObject right = after.getAsJsonObject(scope);
            JsonObject oldMetrics = left.getAsJsonObject("metrics");
            JsonObject newMetrics = right.getAsJsonObject("metrics");
            Set<String> comparable = new TreeSet<>(oldMetrics.keySet());
            comparable.retainAll(newMetrics.keySet());
            if (!sameQuery) {
                comparable.clear();
            }
            JsonArray changes = new JsonArray();
            JsonArray transitions = new JsonArray();
            JsonArray increased = new JsonArray();
            JsonArray decreased = new JsonArray();
            for (String id : comparable) {
                JsonObject oldValue = oldMetrics.getAsJsonObject(id);
                JsonObject newValue = newMetrics.getAsJsonObject(id);
                int comparison = new BigInteger(newValue.get("count").getAsString())
                        .compareTo(new BigInteger(oldValue.get("count").getAsString()));
                if (comparison != 0) {
                    JsonObject change = new JsonObject();
                    change.addProperty("metric", id);
                    change.add("before", oldValue.deepCopy());
                    change.add("after", newValue.deepCopy());
                    changes.add(change);
                    (comparison > 0 ? increased : decreased).add(id);
                    if (!oldValue.get("bucket").equals(newValue.get("bucket"))) {
                        transitions.add(change.deepCopy());
                    }
                }
            }
            Set<String> unverified = strings(left.getAsJsonArray("expected"));
            unverified.addAll(strings(right.getAsJsonArray("expected")));
            unverified.removeAll(comparable);
            JsonObject scoped = new JsonObject();
            scoped.add("changes", changes);
            scoped.add("bucket_transitions", transitions);
            scoped.add("increased", increased);
            scoped.add("decreased", decreased);
            scoped.add("measured", new com.google.gson.Gson().toJsonTree(comparable));
            scoped.add("unverified", new com.google.gson.Gson().toJsonTree(unverified));
            scoped.addProperty("complete", !comparable.isEmpty() && unverified.isEmpty());
            result.add(scope, scoped);
        }
        return result;
    }
}
