package sqlancer.tidb.mixed;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** AI selects predicates; only program-owned capabilities and current exact evidence can prove them. */
public final class TiDBMixedPreparationGoals {
    private static final Set<String> FIELDS = Set.of("capability_id", "field", "comparison", "expected");

    private TiDBMixedPreparationGoals() {
    }

    static JsonObject fields(String kind) {
        JsonObject fields = new JsonObject();
        if (kind.equals("count")) {
            fields.addProperty("count", "non_negative_integer");
        } else if (kind.equals("empty")) {
            fields.addProperty("empty", "boolean");
        } else if (kind.equals("summary")) {
            for (String field : java.util.List.of("group_count", "input_rows", "min_size", "max_size")) {
                fields.addProperty(field, "non_negative_integer");
            }
        }
        return fields;
    }

    private static IllegalArgumentException invalid(String message) {
        return new TiDBDataPreparationProtocol.ValidationException("INVALID_STRUCTURED_GOALS", message);
    }

    private static String string(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalid("structured goal fields must be strings");
        }
        return value.getAsString();
    }

    private static boolean natural(String value) {
        return value.matches("0|[1-9][0-9]*");
    }

    private static JsonObject capability(JsonObject state, String id) {
        for (JsonElement entry : state.getAsJsonArray("available_observations")) {
            JsonObject capability = entry.getAsJsonObject();
            if (id.equals(capability.get("id").getAsString())) {
                return capability;
            }
        }
        throw invalid("unknown goal capability: " + id);
    }

    // Validate predicates against the program-owned catalog, before any write or verification probe.
    public static JsonArray validate(JsonObject state, JsonElement proposed) {
        if (proposed == null || !proposed.isJsonArray()) {
            throw invalid("structured_goals must be an array; use [] for unmeasurable intentions");
        }
        JsonArray goals = proposed.getAsJsonArray();
        if (goals.size() > 64) {
            throw invalid("at most 64 structured goals per response");
        }
        Set<String> seen = new HashSet<>();
        for (JsonElement entry : goals) {
            if (!entry.isJsonObject() || !entry.getAsJsonObject().keySet().equals(FIELDS)) {
                throw invalid("goal requires exactly capability_id, field, comparison, expected");
            }
            JsonObject goal = entry.getAsJsonObject();
            String id = string(goal.get("capability_id"));
            String field = string(goal.get("field"));
            String comparison = string(goal.get("comparison"));
            String expected = string(goal.get("expected"));
            JsonObject capability = capability(state, id);
            JsonObject catalog = capability.getAsJsonObject("goal_fields");
            if (catalog == null || !catalog.has(field)) {
                throw invalid("field is not a measurable goal in capability " + id + ": " + field);
            }
            String type = catalog.get(field).getAsString();
            boolean valid;
            if (type.equals("boolean")) {
                valid = comparison.equals("equals") && Set.of("true", "false").contains(expected);
            } else {
                valid = type.equals("non_negative_integer") && natural(expected)
                        && Set.of("equals", "at_least", "at_most").contains(comparison);
            }
            if (!valid || !seen.add(id + "\n" + field + "\n" + comparison + "\n" + expected)) {
                throw invalid("invalid type/comparison/expected or duplicate structured goal");
            }
        }
        return goals.deepCopy();
    }

    static void validateFields(JsonElement proposed) {
        if (proposed == null || !proposed.isJsonObject()) {
            throw invalid("goal_fields must be an object");
        }
        JsonObject fields = proposed.getAsJsonObject();
        if (!fields.equals(fields("count")) && !fields.equals(fields("empty")) && !fields.equals(fields("summary"))
                && !fields.isEmpty()) {
            throw invalid("unsupported goal_fields schema");
        }
    }

    private static boolean current(JsonObject state, JsonObject observation) {
        for (String key : java.util.List.of("query_id", "schema_revision", "data_revision")) {
            if (!state.get(key).equals(observation.get(key))) {
                return false;
            }
        }
        return "exact".equals(observation.get("status").getAsString()) && observation.get("value").isJsonObject();
    }

    private static String actual(JsonObject observation, String field, String type) {
        JsonObject value = observation.getAsJsonObject("value");
        JsonElement scalar = value.get(field);
        // Group summaries are one complete typed row, not the number of rows in an aggregate result.
        if (scalar == null && value.has("columns") && value.has("rows") && value.has("complete")
                && value.get("complete").getAsBoolean() && value.getAsJsonArray("rows").size() == 1) {
            JsonArray names = value.getAsJsonArray("columns");
            JsonArray row = value.getAsJsonArray("rows").get(0).getAsJsonArray();
            for (int i = 0; i < names.size() && i < row.size(); i++) {
                if (field.equalsIgnoreCase(names.get(i).getAsString()) && row.get(i).isJsonObject()) {
                    JsonObject cell = row.get(i).getAsJsonObject();
                    if (cell.has("type") && Set.of("integer", "decimal").contains(cell.get("type").getAsString())
                            && cell.has("value") && cell.get("value").isJsonPrimitive()
                            && cell.get("value").getAsJsonPrimitive().isString()) {
                        try {
                            BigInteger number = new BigDecimal(cell.get("value").getAsString()).toBigIntegerExact();
                            scalar = new com.google.gson.JsonPrimitive(number.toString());
                        } catch (NumberFormatException | ArithmeticException invalid) {
                            return null;
                        }
                    }
                }
            }
        }
        if (scalar == null || !scalar.isJsonPrimitive()) {
            return null;
        }
        String text = scalar.getAsString();
        if (type.equals("boolean")) {
            return scalar.getAsJsonPrimitive().isBoolean() ? text : null;
        }
        return scalar.getAsJsonPrimitive().isString() && natural(text) ? text : null;
    }

    private static boolean satisfies(String actual, JsonObject goal, String type) {
        String expected = goal.get("expected").getAsString();
        if (type.equals("boolean")) {
            return actual.equals(expected);
        }
        int order = new BigInteger(actual).compareTo(new BigInteger(expected));
        switch (goal.get("comparison").getAsString()) {
        case "at_least":
            return order >= 0;
        case "at_most":
            return order <= 0;
        default:
            return order == 0;
        }
    }

    public static JsonObject evaluate(JsonObject input, JsonArray goals, boolean uncertain) {
        JsonObject state = TiDBDataPreparationProtocol.state(input.toString());
        validate(state, goals);
        JsonArray checks = new JsonArray();
        boolean missing = goals.isEmpty();
        boolean failed = false;
        for (JsonElement entry : goals) {
            JsonObject goal = entry.getAsJsonObject();
            JsonObject check = goal.deepCopy();
            String id = goal.get("capability_id").getAsString();
            String field = goal.get("field").getAsString();
            String type = capability(state, id).getAsJsonObject("goal_fields").get(field).getAsString();
            JsonArray evidence = new JsonArray();
            String actual = null;
            boolean conflict = false;
            if (!uncertain) {
                for (JsonElement fact : state.getAsJsonArray("observations")) {
                    JsonObject observation = fact.getAsJsonObject();
                    if (id.equals(observation.get("capability_id").getAsString()) && current(state, observation)) {
                        String measured = actual(observation, field, type);
                        if (measured != null) {
                            conflict |= actual != null && !actual.equals(measured);
                            actual = measured;
                            evidence.add(observation.deepCopy());
                        }
                    }
                }
            }
            String outcome = "unverified";
            String reason = uncertain ? "write_outcome_uncertain"
                    : conflict ? "conflicting_exact_evidence" : "no_current_exact_measurement";
            if (actual != null && !conflict) {
                boolean satisfied = satisfies(actual, goal, type);
                outcome = satisfied ? "achieved" : "not_achieved";
                reason = "current_exact_measurement";
                failed |= !satisfied;
            } else {
                missing = true;
            }
            check.add("actual",
                    actual == null || conflict ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(actual));
            check.add("evidence", evidence);
            check.addProperty("outcome", outcome);
            check.addProperty("reason", reason);
            checks.add(check);
        }
        JsonObject result = new JsonObject();
        result.addProperty("scope", "structured_goals_only");
        result.addProperty("outcome", failed ? "not_achieved" : missing ? "unverified" : "achieved");
        result.add("checks", checks);
        return result;
    }
}
