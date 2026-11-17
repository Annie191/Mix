package sqlancer.tidb.mixed;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Reports base-data existence separately from logical query-stage witnesses. */
public final class TiDBMixedDataCoverage {
    private TiDBMixedDataCoverage() {
    }

    public static java.util.List<String> measurementIds(TiDBMixedObservations facts) {
        return facts.available().asList().stream().filter(e -> !e.getAsJsonObject().has("parameters"))
                .map(e -> e.getAsJsonObject().get("id").getAsString())
                .filter(id -> !id.startsWith("sample_") && !id.startsWith("freq_"))
                .collect(java.util.stream.Collectors.toList());
    }

    public static JsonObject snapshot(JsonObject packet) {
        Map<String, JsonObject> facts = new LinkedHashMap<>();
        for (JsonElement entry : packet.getAsJsonArray("observations")) {
            JsonObject fact = entry.getAsJsonObject();
            if (fact.has("parameters")) {
                continue; // Parameter-specific diagnostics do not overwrite whole-query coverage facts.
            }
            boolean current = "exact".equals(fact.get("status").getAsString()) && !fact.get("value").isJsonNull();
            for (String key : java.util.List.of("query_id", "schema_revision", "data_revision")) {
                current &= packet.get(key).equals(fact.get(key));
            }
            if (current) {
                facts.put(fact.get("capability_id").getAsString(), fact.getAsJsonObject("value"));
            }
        }
        Map<String, Set<String>> present = new LinkedHashMap<>();
        Map<String, Set<String>> measured = new LinkedHashMap<>();
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        for (String scope : java.util.List.of("base_data", "query_stages")) {
            present.put(scope, new TreeSet<>());
            measured.put(scope, new TreeSet<>());
            expected.put(scope, new TreeSet<>());
        }
        for (JsonElement capability : packet.getAsJsonArray("available_observations")) {
            String id = capability.getAsJsonObject().get("id").getAsString();
            String scope = scope(id);
            if (scope != null) {
                expected.get(scope).add(bucket(id));
            }
            if (id.endsWith("_pairs_after")) {
                expected.get("query_stages").add(id.replace("_pairs_after", "_filtered_matches"));
            }
        }
        for (Map.Entry<String, JsonObject> entry : facts.entrySet()) {
            String id = entry.getKey();
            if (!entry.getValue().has("count")) {
                continue;
            }
            String scope = scope(id);
            if (scope != null) {
                String bucket = bucket(id);
                measured.get(scope).add(bucket);
                if (new BigInteger(entry.getValue().get("count").getAsString()).signum() > 0) {
                    present.get(scope).add(bucket);
                }
            }
            if (id.endsWith("_pairs_before")) {
                String after = id.replace("_pairs_before", "_pairs_after");
                if (facts.containsKey(after)) {
                    String bucket = id.replace("_pairs_before", "_filtered_matches");
                    measured.get("query_stages").add(bucket);
                    if (new BigInteger(entry.getValue().get("count").getAsString())
                            .compareTo(new BigInteger(facts.get(after).get("count").getAsString())) > 0) {
                        present.get("query_stages").add(bucket);
                    }
                }
            }
        }
        if (packet.getAsJsonArray("available_observations").asList().stream()
                .anyMatch(e -> e.getAsJsonObject().get("id").getAsString().equals("query_empty"))) {
            expected.get("query_stages").add("empty_with_input");
        }
        if (facts.containsKey("query_empty") && facts.containsKey("query_input_before")) {
            measured.get("query_stages").add("empty_with_input");
            if (facts.get("query_empty").get("empty").getAsBoolean()
                    && new BigInteger(facts.get("query_input_before").get("count").getAsString()).signum() > 0) {
                present.get("query_stages").add("empty_with_input");
            }
        }
        for (String id : java.util.List.of("groups_before_having", "groups_after_having")) {
            boolean available = packet.getAsJsonArray("available_observations").asList().stream()
                    .anyMatch(e -> e.getAsJsonObject().get("id").getAsString().equals(id));
            if (available) {
                expected.get("query_stages").add(id + "_multiple_groups");
                expected.get("query_stages").add(id + "_multirow_group");
            }
            JsonObject summary = facts.get(id);
            if (summary == null || !summary.has("columns") || !summary.has("complete")
                    || !summary.get("complete").getAsBoolean() || summary.getAsJsonArray("rows").size() != 1) {
                continue;
            }
            JsonArray columns = summary.getAsJsonArray("columns");
            JsonArray row = summary.getAsJsonArray("rows").get(0).getAsJsonArray();
            for (int i = 0; i < columns.size(); i++) {
                String column = columns.get(i).getAsString();
                if (column.equals("group_count") || column.equals("max_size")) {
                    String bucket = id + (column.equals("group_count") ? "_multiple_groups" : "_multirow_group");
                    measured.get("query_stages").add(bucket);
                    if (new java.math.BigDecimal(row.get(i).getAsJsonObject().get("value").getAsString())
                            .compareTo(java.math.BigDecimal.ONE) > 0) {
                        present.get("query_stages").add(bucket);
                    }
                }
            }
        }
        JsonObject result = new JsonObject();
        result.addProperty("scope_version", 2);
        for (String scope : present.keySet()) {
            // Facts from older packets may not have a capability catalog; they still remain measurable.
            expected.get(scope).addAll(measured.get(scope));
            JsonObject scoped = new JsonObject();
            scoped.add("present", new com.google.gson.Gson().toJsonTree(present.get(scope)));
            scoped.add("measured", new com.google.gson.Gson().toJsonTree(measured.get(scope)));
            scoped.add("expected", new com.google.gson.Gson().toJsonTree(expected.get(scope)));
            Set<String> unverified = new TreeSet<>(expected.get(scope));
            unverified.removeAll(measured.get(scope));
            scoped.add("unverified", new com.google.gson.Gson().toJsonTree(unverified));
            result.add(scope, scoped);
        }
        // Existing scene consumers now receive only query-stage witnesses, never base existence.
        for (String field : java.util.List.of("present", "measured", "expected", "unverified")) {
            result.add(field, result.getAsJsonObject("query_stages").get(field).deepCopy());
        }
        JsonArray unsupported = new JsonArray();
        packet.getAsJsonArray("observations").forEach(entry -> {
            JsonObject fact = entry.getAsJsonObject();
            if (fact.get("status").getAsString().equals("unsupported")) {
                unsupported.add(fact.deepCopy());
            }
        });
        result.add("unsupported", unsupported);
        result.add("query_empty", facts.containsKey("query_empty") ? facts.get("query_empty").get("empty")
                : com.google.gson.JsonNull.INSTANCE);
        return result;
    }

    private static String scope(String id) {
        if (id.startsWith("rows_") || id.startsWith("nulls_") || id.startsWith("base_where_boundary_")
                || id.matches("j[0-9]+_(pairs_before|left_unmatched|right_unmatched|left_repeated|right_repeated)")) {
            return "base_data";
        }
        if (id.matches("j[0-9]+_(pairs_after|duplicate_after|left_unmatched_after|right_unmatched_after)")
                || id.startsWith("where_input_nulls_") || id.startsWith("where_output_nulls_")
                || id.startsWith("aggregate_input_nulls_") || id.startsWith("group_input_nulls_")
                || id.matches("aggregate_arg[0-9]+_nulls") || id.startsWith("where_predicate_")
                || id.startsWith("where_boundary_") && !id.endsWith("_unsupported")) {
            return "query_stages";
        }
        return null;
    }

    private static String bucket(String id) {
        return id.startsWith("nulls_") ? "nulls_present_" + id.substring(6) : id;
    }

    private static Set<String> strings(JsonArray array) {
        Set<String> values = new TreeSet<>();
        array.forEach(value -> values.add(value.getAsString()));
        return values;
    }

    private static Set<String> expected(JsonObject snapshot) {
        return strings(snapshot.getAsJsonArray(snapshot.has("expected") ? "expected" : "measured"));
    }

    public static JsonObject difference(JsonObject before, JsonObject after) {
        Set<String> comparable = strings(before.getAsJsonArray("measured"));
        comparable.retainAll(strings(after.getAsJsonArray("measured")));
        Set<String> added = strings(after.getAsJsonArray("present"));
        added.retainAll(comparable);
        added.removeAll(strings(before.getAsJsonArray("present")));
        Set<String> lost = strings(before.getAsJsonArray("present"));
        lost.retainAll(comparable);
        lost.removeAll(strings(after.getAsJsonArray("present")));
        Set<String> unverified = expected(before);
        unverified.addAll(expected(after));
        unverified.removeAll(comparable);
        JsonObject result = new JsonObject();
        result.add("added", new com.google.gson.Gson().toJsonTree(added));
        result.add("lost", new com.google.gson.Gson().toJsonTree(lost));
        result.add("unverified", new com.google.gson.Gson().toJsonTree(unverified));
        result.addProperty("complete", unverified.isEmpty() && !comparable.isEmpty());
        for (String scope : java.util.List.of("base_data", "query_stages")) {
            if (before.has(scope) && after.has(scope)) {
                result.add(scope, difference(before.getAsJsonObject(scope), after.getAsJsonObject(scope)));
            }
        }
        if (result.has("query_stages")) {
            result.addProperty("scope_version", 2);
        }
        return result;
    }
}
