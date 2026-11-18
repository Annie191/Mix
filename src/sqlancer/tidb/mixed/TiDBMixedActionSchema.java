package sqlancer.tidb.mixed;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Dynamic function signatures and the same local shape validation. No proposed SQL is accepted. */
public final class TiDBMixedActionSchema {
    private TiDBMixedActionSchema() {
    }

    static JsonObject type(String name) {
        JsonObject result = new JsonObject();
        result.addProperty("type", name);
        return result;
    }

    static JsonObject choices(JsonElement... values) {
        JsonObject result = new JsonObject();
        JsonArray array = new JsonArray();
        for (JsonElement value : values) {
            array.add(value);
        }
        result.add("anyOf", array);
        return result;
    }

    static JsonObject enumeration(JsonArray values) {
        JsonObject result = type("string");
        result.add("enum", values.deepCopy());
        return result;
    }

    static JsonObject enumeration(String... values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return enumeration(array);
    }

    static JsonObject object(JsonObject properties) {
        JsonObject result = type("object");
        result.add("properties", properties);
        JsonArray required = new JsonArray();
        properties.keySet().forEach(required::add);
        result.add("required", required);
        result.addProperty("additionalProperties", false);
        return result;
    }

    static JsonObject array(JsonObject items) {
        JsonObject result = type("array");
        result.add("items", items);
        return result;
    }

    private static JsonObject cell(String kind) {
        JsonObject properties = new JsonObject();
        properties.add("type", enumeration(kind));
        if (!kind.equals("sqlancer")) {
            JsonObject value = type(kind.equals("null") ? "null" : kind.equals("boolean") ? "boolean" : "string");
            if (kind.equals("integer") || kind.equals("decimal")) {
                value.addProperty("pattern",
                        kind.equals("integer") ? "^-?(0|[1-9][0-9]*)$" : "^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$");
            }
            properties.add("value", value);
        }
        return object(properties);
    }

    private static JsonObject parameters(JsonObject capability) {
        JsonObject properties = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : capability.getAsJsonObject("parameters").entrySet()) {
            JsonObject spec = entry.getValue().getAsJsonObject();
            String kind = spec.get("kind").getAsString();
            properties.add(entry.getKey(), kind.equals("enum") ? enumeration(spec.getAsJsonArray("values"))
                    : choices(cell(kind), cell("null")));
        }
        return object(properties);
    }

    private static JsonObject requests(JsonObject state, boolean strict) {
        JsonArray variants = new JsonArray();
        JsonArray staticIds = new JsonArray();
        for (JsonElement entry : state.getAsJsonArray("available_observations")) {
            JsonObject capability = entry.getAsJsonObject();
            if (!capability.has("parameters")) {
                staticIds.add(capability.get("id"));
            } else {
                JsonObject properties = new JsonObject();
                properties.add("capability_id", enumeration(capability.get("id").getAsString()));
                properties.add("parameters", parameters(capability));
                variants.add(object(properties));
            }
        }
        if (!staticIds.isEmpty()) {
            if (strict) {
                JsonObject properties = new JsonObject();
                properties.add("capability_id", enumeration(staticIds));
                properties.add("parameters", type("null"));
                variants.add(object(properties));
            } else {
                variants.add(enumeration(staticIds));
            }
        }
        JsonObject items = new JsonObject();
        items.add("anyOf", variants);
        return array(items);
    }

    private static JsonObject goals(JsonObject state) {
        JsonArray variants = new JsonArray();
        for (JsonElement entry : state.getAsJsonArray("available_observations")) {
            JsonObject capability = entry.getAsJsonObject();
            if (!capability.has("goal_fields")) {
                continue;
            }
            for (Map.Entry<String, JsonElement> field : capability.getAsJsonObject("goal_fields").entrySet()) {
                JsonObject properties = new JsonObject();
                properties.add("capability_id", enumeration(capability.get("id").getAsString()));
                properties.add("field", enumeration(field.getKey()));
                boolean bool = field.getValue().getAsString().equals("boolean");
                properties.add("comparison",
                        bool ? enumeration("equals") : enumeration("equals", "at_least", "at_most"));
                JsonObject expected = bool ? enumeration("true", "false") : type("string");
                if (!bool) {
                    expected.addProperty("pattern", "^(0|[1-9][0-9]*)$");
                }
                properties.add("expected", expected);
                if (capability.has("parameters")) {
                    properties.add("parameters", parameters(capability));
                }
                variants.add(object(properties));
            }
        }
        JsonObject items = new JsonObject();
        items.add("anyOf", variants);
        return array(items);
    }

    private static JsonObject operations(JsonObject state, boolean strict) {
        JsonArray variants = new JsonArray();
        for (JsonElement entry : state.getAsJsonObject("schema").getAsJsonArray("tables")) {
            JsonObject table = entry.getAsJsonObject();
            JsonObject columns = new JsonObject();
            for (JsonElement item : table.getAsJsonArray("columns")) {
                JsonObject column = item.getAsJsonObject();
                if (column.get("generated").getAsBoolean()) {
                    continue;
                }
                JsonArray types = new JsonArray();
                types.add(cell(column.get("value_kind").getAsString()));
                if (column.get("nullable").getAsBoolean()) {
                    types.add(cell("null"));
                }
                if (strict) {
                    types.add(cell("sqlancer"));
                }
                JsonObject value = new JsonObject();
                value.add("anyOf", types);
                columns.add(column.get("name").getAsString(), value);
            }
            if (columns.isEmpty()) {
                continue;
            }
            JsonObject row = object(columns);
            if (!strict) {
                row.add("required", new JsonArray());
            }
            row.addProperty("minProperties", 1);
            JsonObject properties = new JsonObject();
            properties.add("table", enumeration(table.get("name").getAsString()));
            JsonObject rows = array(row);
            rows.addProperty("minItems", 1);
            properties.add("rows", rows);
            variants.add(object(properties));
        }
        JsonObject items = new JsonObject();
        items.add("anyOf", variants);
        return array(items);
    }

    private static JsonObject signature(JsonObject state, String action) {
        JsonObject properties = new JsonObject();
        properties.add("reason", type("string"));
        properties.add("evidence_ids", array(type("string")));
        if (action.equals("stop")) {
            properties.add("stop_reason", type("string"));
        } else {
            properties.add("goal", type("string"));
            if (action.equals("observe")) {
                JsonObject requests = requests(state, true);
                requests.addProperty("minItems", 1);
                properties.add("observation_requests", requests);
            } else {
                JsonObject append = operations(state, true);
                append.addProperty("minItems", 1);
                append.addProperty("description", "Sum of ALL rows across tables must be <= "
                        + state.getAsJsonObject("remaining_budget").get("insert_rows").getAsLong());
                properties.add("operations", append);
                properties.add("structured_goals", goals(state));
                properties.add("expected_effect", type("string"));
                JsonObject verification = requests(state, true);
                verification.addProperty("minItems", 1);
                properties.add("verification_requests", verification);
                JsonObject basis = new JsonObject();
                for (String field : List.of("join_witness", "where_witness", "group_witness", "constraint_check",
                        "measurement")) {
                    JsonObject explanation = type("string");
                    explanation.addProperty("pattern", "[\\s\\S]*\\S[\\s\\S]*");
                    basis.add(field, explanation);
                }
                properties.add("feasibility", object(basis));
            }
        }
        return object(properties);
    }

    private static JsonElement serverSchema(JsonElement schema) {
        if (schema.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : schema.getAsJsonObject().entrySet()) {
                if (!List.of("minItems", "maxItems", "minProperties").contains(entry.getKey())) {
                    result.add(entry.getKey(), serverSchema(entry.getValue()));
                }
            }
            return result;
        }
        if (schema.isJsonArray()) {
            JsonArray result = new JsonArray();
            schema.getAsJsonArray().forEach(entry -> result.add(serverSchema(entry)));
            return result;
        }
        return schema.deepCopy();
    }

    public static JsonArray tools(JsonObject state) {
        JsonArray tools = new JsonArray();
        for (String action : List.of("observe", "append_rows", "stop")) {
            JsonObject function = new JsonObject();
            function.addProperty("name", action);
            function.addProperty("strict", true);
            function.addProperty("description", action.equals("append_rows")
                    ? "Append rows only; justify full JOIN/WHERE/GROUP BY witnesses. Program measures afterwards without another API call."
                    : action.equals("observe") ? "Request program-owned read-only facts; no writes"
                            : "Finish preparation; keep existing data for differential testing");
            function.add("parameters", serverSchema(signature(state, action)));
            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.add("function", function);
            tools.add(tool);
        }
        return tools;
    }

    static String receivedType(JsonElement value) {
        if (value == null) {
            return "missing";
        }
        if (value.isJsonNull()) {
            return "null";
        }
        if (value.isJsonArray()) {
            return "array";
        }
        if (value.isJsonObject()) {
            return "object";
        }
        return value.getAsJsonPrimitive().isString() ? "string"
                : value.getAsJsonPrimitive().isBoolean() ? "boolean" : "number";
    }

    static void fail(String path, String code, String expected, JsonElement actual, JsonObject schema) {
        JsonObject details = new JsonObject();
        details.addProperty("path", path);
        details.addProperty("code", code);
        details.addProperty("expected", expected);
        details.addProperty("received_type", receivedType(actual));
        if (schema.has("enum")) {
            details.add("allowed_values", schema.get("enum").deepCopy());
            details.add("example", schema.getAsJsonArray("enum").get(0).deepCopy());
        } else {
            details.add("example", example(schema));
        }
        throw new TiDBDataPreparationProtocol.ValidationException(code, path + ": expected " + expected, details);
    }

    private static JsonElement example(JsonObject schema) {
        if (schema.has("anyOf") && !schema.getAsJsonArray("anyOf").isEmpty()) {
            return example(schema.getAsJsonArray("anyOf").get(0).getAsJsonObject());
        }
        if (schema.has("enum")) {
            return schema.getAsJsonArray("enum").get(0).deepCopy();
        }
        String type = schema.has("type") ? schema.get("type").getAsString() : "null";
        if (type.equals("object")) {
            JsonObject result = new JsonObject();
            if (!schema.has("properties")) {
                return result;
            }
            for (Map.Entry<String, JsonElement> field : schema.getAsJsonObject("properties").entrySet()) {
                result.add(field.getKey(), example(field.getValue().getAsJsonObject()));
            }
            return result;
        }
        if (type.equals("array")) {
            JsonArray result = new JsonArray();
            result.add(example(schema.getAsJsonObject("items")));
            return result;
        }
        return type.equals("null") ? JsonNull.INSTANCE : type.equals("boolean")
                ? new com.google.gson.JsonPrimitive(false) : new com.google.gson.JsonPrimitive("1");
    }

    private static boolean discriminator(JsonObject schema, JsonElement value) {
        if (!value.isJsonObject() || !schema.has("properties")) {
            return true;
        }
        for (String key : List.of("capability_id", "field", "table", "type")) {
            JsonObject properties = schema.getAsJsonObject("properties");
            if (properties.has(key) && properties.getAsJsonObject(key).has("enum") && value.getAsJsonObject().has(key)
                    && !properties.getAsJsonObject(key).getAsJsonArray("enum")
                            .contains(value.getAsJsonObject().get(key))) {
                return false;
            }
        }
        return true;
    }

    static void validate(JsonElement value, JsonObject schema, String path) {
        if (schema.has("anyOf")) {
            TiDBDataPreparationProtocol.ValidationException closest = null;
            for (JsonElement branch : schema.getAsJsonArray("anyOf")) {
                if (value != null && !discriminator(branch.getAsJsonObject(), value)) {
                    continue;
                }
                try {
                    validate(value, branch.getAsJsonObject(), path);
                    return;
                } catch (TiDBDataPreparationProtocol.ValidationException error) {
                    if (closest == null || error.details.get("path").getAsString().length() > closest.details
                            .get("path").getAsString().length()) {
                        closest = error;
                    }
                }
            }
            if (closest != null) {
                throw closest;
            }
            if (value != null && value.isJsonObject()) {
                for (String key : List.of("capability_id", "field", "table", "type")) {
                    if (!value.getAsJsonObject().has(key)) {
                        continue;
                    }
                    JsonObject others = value.getAsJsonObject().deepCopy();
                    others.remove(key);
                    JsonArray allowed = new JsonArray();
                    for (JsonElement entry : schema.getAsJsonArray("anyOf")) {
                        JsonObject branch = entry.getAsJsonObject();
                        if (discriminator(branch, others) && branch.has("properties")
                                && branch.getAsJsonObject("properties").has(key)) {
                            JsonObject field = branch.getAsJsonObject("properties").getAsJsonObject(key);
                            if (field.has("enum")) {
                                field.getAsJsonArray("enum").forEach(choice -> {
                                    if (!allowed.contains(choice)) {
                                        allowed.add(choice);
                                    }
                                });
                            }
                        }
                    }
                    if (!allowed.isEmpty() && !allowed.contains(value.getAsJsonObject().get(key))) {
                        fail(path + "." + key, "ENUM_MISMATCH", "an advertised value", value.getAsJsonObject().get(key),
                                enumeration(allowed));
                    }
                }
            }
            fail(path, "NO_MATCHING_VARIANT", "one of the advertised typed variants", value, schema);
        }
        String type = schema.get("type").getAsString();
        if (!receivedType(value).equals(type)) {
            fail(path, "TYPE_MISMATCH", type, value, schema);
        }
        if (schema.has("enum") && !schema.getAsJsonArray("enum").contains(value)) {
            fail(path, "ENUM_MISMATCH", "an allowed value", value, schema);
        }
        if (schema.has("pattern") && !value.getAsString().matches(schema.get("pattern").getAsString())) {
            fail(path, "VALUE_FORMAT", schema.get("pattern").getAsString(), value, schema);
        }
        if (type.equals("object")) {
            JsonObject object = value.getAsJsonObject();
            JsonObject properties = schema.getAsJsonObject("properties");
            for (JsonElement key : schema.getAsJsonArray("required")) {
                if (!object.has(key.getAsString())) {
                    fail(path + "." + key.getAsString(), "REQUIRED_FIELD", "required field", null,
                            properties.getAsJsonObject(key.getAsString()));
                }
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (!properties.has(entry.getKey())) {
                    fail(path + "." + entry.getKey(), "UNKNOWN_FIELD", "an advertised field", entry.getValue(), schema);
                }
                validate(entry.getValue(), properties.getAsJsonObject(entry.getKey()), path + "." + entry.getKey());
            }
            if (schema.has("minProperties") && object.size() < schema.get("minProperties").getAsInt()) {
                fail(path, "EMPTY_OBJECT", "at least one writable column", value, schema);
            }
        } else if (type.equals("array")) {
            JsonArray array = value.getAsJsonArray();
            if (schema.has("minItems") && array.size() < schema.get("minItems").getAsInt()) {
                fail(path, "EMPTY_ARRAY", "a nonempty array", value, schema);
            }
            for (int i = 0; i < array.size(); i++) {
                validate(array.get(i), schema.getAsJsonObject("items"), path + "[" + i + "]");
            }
        }
    }

    static JsonObject normalize(JsonObject wrapper, JsonObject state) {
        if (!wrapper.has("tool_calls") || !wrapper.get("tool_calls").isJsonArray()) {
            fail("$.tool_calls", "TYPE_MISMATCH", "array", wrapper.get("tool_calls"), array(type("object")));
        }
        JsonArray calls = wrapper.getAsJsonArray("tool_calls");
        if (calls == null || calls.size() != 1) {
            fail("$.tool_calls", "TOOL_CALL_COUNT", "exactly one action", calls, array(type("object")));
        }
        if (!calls.get(0).isJsonObject() || !calls.get(0).getAsJsonObject().has("function")
                || !calls.get(0).getAsJsonObject().get("function").isJsonObject()) {
            fail("$.tool_calls[0].function", "TYPE_MISMATCH", "object", calls.get(0), type("object"));
        }
        JsonObject function = calls.get(0).getAsJsonObject().getAsJsonObject("function");
        validate(function.get("name"), enumeration("observe", "append_rows", "stop"), "$.tool_calls[0].function.name");
        validate(function.get("arguments"), type("string"), "$.tool_calls[0].function.arguments");
        String action = function.get("name").getAsString();
        if (!List.of("observe", "append_rows", "stop").contains(action)) {
            throw new TiDBDataPreparationProtocol.ValidationException("UNKNOWN_ACTION", "unknown function action");
        }
        JsonObject arguments;
        try {
            arguments = TiDBDataPreparationProtocol.parse(function.get("arguments").getAsString(), 32_000);
        } catch (IllegalArgumentException malformed) {
            fail("$.tool_calls[0].function.arguments", "INVALID_ARGUMENTS_JSON", "a JSON object encoded as a string",
                    function.get("arguments"), signature(state, action));
            throw new AssertionError("fail must throw");
        }
        validate(arguments, signature(state, action), "$");
        JsonObject plan = new JsonObject();
        for (String key : List.of("protocol_version", "request_id", "case_id", "query_id", "schema_revision",
                "data_revision")) {
            plan.add(key, state.get(key).deepCopy());
        }
        plan.addProperty("decision", action);
        for (String key : List.of("reason", "evidence_ids")) {
            plan.add(key, arguments.get(key).deepCopy());
        }
        plan.addProperty("goal", arguments.has("goal") ? arguments.get("goal").getAsString() : "Finish preparation");
        plan.addProperty("expected_effect",
                arguments.has("expected_effect") ? arguments.get("expected_effect").getAsString()
                        : "Program measures requested facts; no success claim");
        for (String key : List.of("observation_requests", "operations", "verification_requests", "structured_goals")) {
            plan.add(key, arguments.has(key) ? arguments.get(key).deepCopy() : new JsonArray());
        }
        plan.add("verification_limit", JsonNull.INSTANCE);
        plan.add("stop_reason", arguments.has("stop_reason") ? arguments.get("stop_reason") : JsonNull.INSTANCE);
        if (arguments.has("feasibility")) {
            plan.add("feasibility", arguments.get("feasibility").deepCopy());
        }
        for (String key : List.of("observation_requests", "verification_requests")) {
            JsonArray entries = plan.getAsJsonArray(key);
            for (int i = 0; i < entries.size(); i++) {
                JsonObject request = entries.get(i).getAsJsonObject();
                JsonObject capability = TiDBMixedObservationRequest.capability(state,
                        request.get("capability_id").getAsString());
                if (!capability.has("parameters")) {
                    entries.set(i, request.get("capability_id").deepCopy());
                }
            }
        }
        for (JsonElement operation : plan.getAsJsonArray("operations")) {
            for (JsonElement row : operation.getAsJsonObject().getAsJsonArray("rows")) {
                row.getAsJsonObject().entrySet().removeIf(
                        entry -> entry.getValue().getAsJsonObject().get("type").getAsString().equals("sqlancer"));
            }
        }
        return plan;
    }

    static void legacyShape(JsonObject plan, JsonObject state) {
        JsonObject properties = new JsonObject();
        for (String key : List.of("reason", "goal", "expected_effect")) {
            properties.add(key, type("string"));
        }
        properties.add("operations", operations(state, false));
        properties.add("observation_requests", requests(state, false));
        properties.add("verification_requests", requests(state, false));
        for (String field : List.of("observation_requests", "verification_requests")) {
            if (plan.has(field) && plan.get(field).isJsonArray()) {
                JsonArray entries = plan.getAsJsonArray(field);
                for (int i = 0; i < entries.size(); i++) {
                    JsonElement entry = entries.get(i);
                    if (entry.isJsonPrimitive() && entry.getAsJsonPrimitive().isString()) {
                        for (JsonElement item : state.getAsJsonArray("available_observations")) {
                            JsonObject capability = item.getAsJsonObject();
                            if (capability.get("id").equals(entry) && capability.has("parameters")) {
                                fail("$." + field + "[" + i + "].parameters", "REQUIRED_FIELD", "parameters object",
                                        null, parameters(capability));
                            }
                        }
                    }
                }
            }
        }
        if (plan.has("structured_goals")) {
            properties.add("structured_goals", goals(state));
        }
        for (Map.Entry<String, JsonElement> field : properties.entrySet()) {
            validate(plan.get(field.getKey()), field.getValue().getAsJsonObject(), "$." + field.getKey());
        }
        if (plan.has("decision") && plan.get("decision").isJsonPrimitive()
                && plan.get("decision").getAsString().equals("observe")) {
            validate(plan.get("verification_limit"), type("null"), "$.verification_limit");
        }
    }
}
