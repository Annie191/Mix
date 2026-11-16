package sqlancer.tidb.mixed;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Data facts only: never combines engine coverage, INSERT success, or model explanations. */
public final class TiDBMixedDataCoverage {
    private TiDBMixedDataCoverage() {
    }

    public static java.util.List<String> measurementIds(TiDBMixedObservations facts) {
        return facts.available().asList().stream().map(e -> e.getAsJsonObject().get("id").getAsString())
                .filter(id -> !id.startsWith("sample_") && !id.startsWith("freq_"))
                .collect(java.util.stream.Collectors.toList());
    }

    public static JsonObject snapshot(JsonObject packet) {
        Map<String, JsonObject> facts = new LinkedHashMap<>();
        for (JsonElement entry : packet.getAsJsonArray("observations")) {
            JsonObject fact = entry.getAsJsonObject();
            boolean current = "exact".equals(fact.get("status").getAsString()) && !fact.get("value").isJsonNull();
            for (String key : java.util.List.of("query_id", "schema_revision", "data_revision")) {
                current &= packet.get(key).equals(fact.get(key));
            }
            if (current) {
                facts.put(fact.get("capability_id").getAsString(), fact.getAsJsonObject("value"));
            }
        }
        Set<String> present = new TreeSet<>();
        Set<String> measured = new TreeSet<>();
        for (Map.Entry<String, JsonObject> entry : facts.entrySet()) {
            String id = entry.getKey();
            if (!entry.getValue().has("count")) {
                continue;
            }
            if (id.matches(
                    "j[0-9]+_(pairs_before|pairs_after|left_unmatched|right_unmatched|left_repeated|right_repeated|duplicate_after)")
                    || id.startsWith("nulls_")) {
                String bucket = id.startsWith("nulls_") ? "nulls_present_" + id.substring(6) : id;
                measured.add(bucket);
                if (new BigInteger(entry.getValue().get("count").getAsString()).signum() > 0) {
                    present.add(bucket);
                }
            }
            if (id.endsWith("_pairs_before")) {
                String after = id.replace("_pairs_before", "_pairs_after");
                if (facts.containsKey(after)) {
                    String bucket = id.replace("_pairs_before", "_filtered_matches");
                    measured.add(bucket);
                    if (new BigInteger(entry.getValue().get("count").getAsString())
                            .compareTo(new BigInteger(facts.get(after).get("count").getAsString())) > 0) {
                        present.add(bucket);
                    }
                }
            }
        }
        if (facts.containsKey("query_empty") && facts.containsKey("query_input_before")) {
            measured.add("empty_with_input");
            if (facts.get("query_empty").get("empty").getAsBoolean()
                    && new BigInteger(facts.get("query_input_before").get("count").getAsString()).signum() > 0) {
                present.add("empty_with_input");
            }
        }
        for (String id : java.util.List.of("groups_before_having", "groups_after_having")) {
            JsonObject summary = facts.get(id);
            if (summary == null || !summary.has("columns") || summary.getAsJsonArray("rows").size() != 1) {
                continue;
            }
            JsonArray columns = summary.getAsJsonArray("columns");
            JsonArray row = summary.getAsJsonArray("rows").get(0).getAsJsonArray();
            for (int i = 0; i < columns.size(); i++) {
                String column = columns.get(i).getAsString();
                if (column.equals("group_count") || column.equals("max_size")) {
                    String bucket = id + (column.equals("group_count") ? "_multiple_groups" : "_multirow_group");
                    measured.add(bucket);
                    if (new java.math.BigDecimal(row.get(i).getAsJsonObject().get("value").getAsString())
                            .compareTo(java.math.BigDecimal.ONE) > 0) {
                        present.add(bucket);
                    }
                }
            }
        }
        JsonObject result = new JsonObject();
        result.add("present", new com.google.gson.Gson().toJsonTree(present));
        result.add("measured", new com.google.gson.Gson().toJsonTree(measured));
        result.add("query_empty", facts.containsKey("query_empty") ? facts.get("query_empty").get("empty")
                : com.google.gson.JsonNull.INSTANCE);
        return result;
    }

    private static Set<String> strings(JsonArray array) {
        Set<String> values = new TreeSet<>();
        array.forEach(value -> values.add(value.getAsString()));
        return values;
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
        JsonObject result = new JsonObject();
        result.add("added", new com.google.gson.Gson().toJsonTree(added));
        result.add("lost", new com.google.gson.Gson().toJsonTree(lost));
        result.addProperty("complete", comparable.equals(strings(before.getAsJsonArray("measured")))
                && comparable.equals(strings(after.getAsJsonArray("measured"))) && !comparable.isEmpty());
        return result;
    }
}
