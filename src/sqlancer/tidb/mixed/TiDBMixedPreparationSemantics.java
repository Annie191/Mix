package sqlancer.tidb.mixed;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Conservative metadata proofs; model explanations remain proposals, never observed coverage. */
final class TiDBMixedPreparationSemantics {
    private TiDBMixedPreparationSemantics() {
    }

    static JsonObject analyze(JsonObject state) {
        JsonObject result = new JsonObject();
        result.addProperty("status", "unverified");
        result.add("max_input_rows_per_group", JsonNull.INSTANCE);
        JsonArray proof = new JsonArray();
        result.add("group_uniqueness_proof", proof);
        JsonObject query = state.getAsJsonObject("query");
        JsonObject schema = state.getAsJsonObject("schema");
        Set<String> grouping = new HashSet<>();
        query.getAsJsonArray("group_by").forEach(key -> grouping.add(key.getAsString()));
        boolean bound = schema.get("complete").getAsBoolean() && !grouping.isEmpty();
        for (JsonElement entry : query.getAsJsonArray("joins")) {
            String kind = entry.getAsJsonObject().get("type").getAsString();
            bound &= List.of("INNER", "STRAIGHT", "CROSS", "NATURAL_INNER").contains(kind);
        }
        JsonArray composite = new JsonArray();
        result.add("composite_key_guidance", composite);
        JsonArray natural = new JsonArray();
        result.add("natural_join_keys", natural);
        for (JsonElement entry : schema.getAsJsonArray("tables")) {
            JsonObject table = entry.getAsJsonObject();
            String name = table.get("name").getAsString();
            boolean unique = false;
            for (JsonElement constraint : table.getAsJsonArray("constraints")) {
                JsonObject key = constraint.getAsJsonObject();
                if (!List.of("primary_key", "unique").contains(key.get("kind").getAsString())
                        || !key.get("enforced").getAsBoolean() || !key.has("key_parts")
                        || key.getAsJsonArray("key_parts").isEmpty()) {
                    continue;
                }
                boolean complete = table.get("metadata_complete").getAsBoolean();
                JsonArray groupedParts = new JsonArray();
                JsonArray otherParts = new JsonArray();
                JsonArray columns = new JsonArray();
                for (JsonElement part : key.getAsJsonArray("key_parts")) {
                    JsonObject component = part.getAsJsonObject();
                    if (component.get("column").isJsonNull() || !component.get("expression").isJsonNull()
                            || !component.get("prefix_length").isJsonNull()) {
                        complete = false;
                        continue;
                    }
                    String column = component.get("column").getAsString();
                    (grouping.contains(name + "." + column) ? groupedParts : otherParts).add(name + "." + column);
                    boolean notNull = table.getAsJsonArray("columns").asList().stream()
                            .map(JsonElement::getAsJsonObject).anyMatch(c -> c.get("name").getAsString().equals(column)
                                    && !c.get("nullable").getAsBoolean());
                    complete &= notNull && grouping.contains(name + "." + column);
                    columns.add(name + "." + column);
                }
                if (!groupedParts.isEmpty() && !otherParts.isEmpty()) {
                    JsonObject guidance = new JsonObject();
                    guidance.addProperty("table", name);
                    guidance.add("constraint", key.get("name"));
                    guidance.add("grouped_components", groupedParts);
                    guidance.add("ungrouped_components", otherParts);
                    guidance.addProperty("meaning",
                            "Uniqueness applies to the whole tuple; grouped components may stay equal while ungrouped components differ, subject to all actual constraints");
                    composite.add(guidance);
                }
                if (complete) {
                    JsonObject fact = new JsonObject();
                    fact.addProperty("table", name);
                    fact.addProperty("constraint", key.get("name").getAsString());
                    fact.add("grouped_non_nullable_key", columns);
                    proof.add(fact);
                    unique = true;
                    break;
                }
            }
            bound &= unique;
        }
        if (bound && !schema.getAsJsonArray("tables").isEmpty()) {
            result.addProperty("status", "proved_from_constraints");
            result.addProperty("max_input_rows_per_group", "1");
        }
        for (JsonElement entry : query.getAsJsonArray("joins")) {
            JsonObject join = entry.getAsJsonObject();
            if (!join.get("type").getAsString().startsWith("NATURAL") || join.getAsJsonArray("left_tables").size() != 1
                    || join.getAsJsonArray("right_tables").size() != 1) {
                continue;
            }
            JsonObject left = table(schema, join.getAsJsonArray("left_tables").get(0).getAsString());
            JsonObject right = table(schema, join.getAsJsonArray("right_tables").get(0).getAsString());
            JsonObject keys = new JsonObject();
            keys.add("join_id", join.get("id"));
            JsonArray pairs = new JsonArray();
            for (JsonElement l : left.getAsJsonArray("columns")) {
                for (JsonElement r : right.getAsJsonArray("columns")) {
                    JsonObject lc = l.getAsJsonObject();
                    JsonObject rc = r.getAsJsonObject();
                    if (lc.get("name").getAsString().equalsIgnoreCase(rc.get("name").getAsString())) {
                        JsonObject pair = new JsonObject();
                        pair.add("column", lc.get("name"));
                        pair.add("left_type", lc.get("sql_type"));
                        pair.add("right_type", rc.get("sql_type"));
                        pairs.add(pair);
                    }
                }
            }
            keys.add("all_common_columns", pairs);
            keys.addProperty("comparison_status", "must_measure_full_values_not_sample_prefixes");
            natural.add(keys);
        }
        return result;
    }

    private static JsonObject table(JsonObject schema, String name) {
        return schema.getAsJsonArray("tables").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(item -> item.get("name").getAsString().equals(name)).findFirst().orElseThrow();
    }

    static void validateGoals(JsonObject plan, JsonObject state) {
        JsonObject analysis = analyze(state);
        JsonObject planned = TiDBMixedPlannedGroups.analyze(plan, state);
        boolean constraintBound = !analysis.get("max_input_rows_per_group").isJsonNull();
        if (!constraintBound && planned.get("upper_bound").isJsonNull()) {
            return;
        }
        BigInteger bound = constraintBound ? BigInteger.ONE : new BigInteger(planned.get("upper_bound").getAsString());
        JsonArray goals = plan.getAsJsonArray("structured_goals");
        for (int i = 0; i < goals.size(); i++) {
            JsonObject goal = goals.get(i).getAsJsonObject();
            if (List.of("groups_before_having", "groups_after_having").contains(goal.get("capability_id").getAsString())
                    && List.of("min_size", "max_size").contains(goal.get("field").getAsString())
                    && !goal.get("comparison").getAsString().equals("at_most")
                    && new BigInteger(goal.get("expected").getAsString()).compareTo(bound) > 0) {
                JsonObject details = new JsonObject();
                details.addProperty("path", "$.structured_goals[" + i + "].expected");
                String code = constraintBound ? "UNREACHABLE_GROUP_SIZE" : "GROUP_PLAN_CANNOT_REACH_GOAL";
                details.addProperty("code", code);
                details.addProperty("expected", "group size <= " + bound
                        + "; keep ALL GROUP BY values equal for rows intended for the same group; vary ungrouped composite-key components when legal");
                details.addProperty("received_type", "string");
                details.addProperty("example", bound.toString());
                details.add("constraint_proof", analysis);
                details.add("planned_group_bound", planned);
                throw new TiDBDataPreparationProtocol.ValidationException(code,
                        "Matching pairs with different full GROUP BY keys form different groups", details);
            }
        }
    }
}
