package sqlancer.tidb.mixed;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Typed requests and canonical identities; no request field contains executable SQL. */
final class TiDBMixedObservationRequest {
    private TiDBMixedObservationRequest() {
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new TiDBDataPreparationProtocol.ValidationException("INVALID_OBSERVATION_REQUESTS", message);
        }
    }

    static JsonObject valueSchema(String kind) {
        JsonObject schema = new JsonObject();
        schema.addProperty("kind", kind);
        schema.addProperty("nullable", true);
        return schema;
    }

    static JsonObject enumSchema(String... values) {
        JsonObject schema = new JsonObject();
        schema.addProperty("kind", "enum");
        JsonArray choices = new JsonArray();
        for (String value : values) {
            choices.add(value);
        }
        schema.add("values", choices);
        return schema;
    }

    static void schema(JsonElement element) {
        require(element.isJsonObject() && !element.getAsJsonObject().isEmpty(), "invalid parameter schema");
        for (JsonElement item : element.getAsJsonObject().asMap().values()) {
            require(item.isJsonObject(), "invalid parameter schema entry");
            JsonObject spec = item.getAsJsonObject();
            String kind = spec.has("kind") ? spec.get("kind").getAsString() : "";
            if (kind.equals("enum")) {
                require(spec.keySet().equals(Set.of("kind", "values")) && spec.get("values").isJsonArray()
                        && !spec.getAsJsonArray("values").isEmpty(), "invalid parameter choices");
                for (JsonElement choice : spec.getAsJsonArray("values")) {
                    require(choice.isJsonPrimitive() && choice.getAsJsonPrimitive().isString(), "invalid choice");
                }
            } else {
                require(Set.of("integer", "decimal", "text", "boolean", "binary").contains(kind)
                        && spec.equals(valueSchema(kind)), "invalid parameter type");
            }
        }
    }

    static String capabilityId(JsonElement request) {
        if (request.isJsonPrimitive() && request.getAsJsonPrimitive().isString()) {
            return request.getAsString();
        }
        require(request.isJsonObject(), "request must be a capability ID or typed request object");
        JsonObject object = request.getAsJsonObject();
        require(object.keySet().equals(Set.of("capability_id", "parameters"))
                && object.get("capability_id").isJsonPrimitive()
                && object.get("capability_id").getAsJsonPrimitive().isString(), "invalid request fields");
        return object.get("capability_id").getAsString();
    }

    static JsonObject parameters(JsonObject capability, JsonElement value) {
        if (!capability.has("parameters")) {
            require(value == null, "static capability cannot accept parameters");
            return new JsonObject();
        }
        JsonObject schemas = capability.getAsJsonObject("parameters");
        require(value != null && value.isJsonObject() && value.getAsJsonObject().keySet().equals(schemas.keySet()),
                "missing or unexpected observation parameters");
        JsonObject parameters = value.getAsJsonObject();
        for (String name : schemas.keySet()) {
            JsonObject spec = schemas.getAsJsonObject(name);
            JsonElement argument = parameters.get(name);
            if (spec.get("kind").getAsString().equals("enum")) {
                require(argument.isJsonPrimitive() && argument.getAsJsonPrimitive().isString()
                        && spec.getAsJsonArray("values").contains(argument), "unknown parameter choice: " + name);
            } else {
                TiDBDataPreparationProtocol.typedValue(argument);
                String type = argument.getAsJsonObject().get("type").getAsString();
                require(type.equals("null") || type.equals(spec.get("kind").getAsString()),
                        "parameter type mismatch: " + name);
            }
        }
        return canonical(parameters).getAsJsonObject();
    }

    static JsonObject capability(JsonObject state, String id) {
        for (JsonElement entry : state.getAsJsonArray("available_observations")) {
            JsonObject capability = entry.getAsJsonObject();
            if (id.equals(capability.get("id").getAsString())) {
                return capability;
            }
        }
        throw new TiDBDataPreparationProtocol.ValidationException("INVALID_OBSERVATION_REQUESTS",
                "unknown capability: " + id);
    }

    static void validate(JsonObject state, JsonElement entries, long budget) {
        require(entries.isJsonArray() && entries.getAsJsonArray().size() <= budget, "over-budget observation requests");
        Set<String> seen = new HashSet<>();
        for (JsonElement request : entries.getAsJsonArray()) {
            JsonObject capability = capability(state, capabilityId(request));
            JsonElement parameters = request.isJsonObject() ? request.getAsJsonObject().get("parameters") : null;
            JsonObject normalized = parameters(capability, parameters);
            require(seen.add(key(capabilityId(request), normalized)), "duplicate observation request");
        }
    }

    private static JsonElement canonical(JsonElement element) {
        if (!element.isJsonObject()) {
            return element.deepCopy();
        }
        JsonObject object = element.getAsJsonObject();
        JsonObject result = new JsonObject();
        new TreeMap<>(object.asMap()).forEach((key, value) -> result.add(key, canonical(value)));
        if (object.has("type") && object.has("value") && object.get("type").isJsonPrimitive()
                && Set.of("integer", "decimal").contains(object.get("type").getAsString())) {
            result.addProperty("value",
                    new BigDecimal(object.get("value").getAsString()).stripTrailingZeros().toPlainString());
        }
        return result;
    }

    static String key(String id, JsonElement parameters) {
        return id + "\n" + (parameters == null ? "{}" : canonical(parameters).toString());
    }

    static String key(JsonElement request) {
        return key(capabilityId(request), request.isJsonObject() ? request.getAsJsonObject().get("parameters") : null);
    }

    static String factKey(JsonObject fact) {
        return key(fact.get("capability_id").getAsString(), fact.get("parameters"));
    }

    static JsonElement goalRequest(JsonObject goal) {
        if (!goal.has("parameters")) {
            return goal.get("capability_id").deepCopy();
        }
        JsonObject request = new JsonObject();
        request.add("capability_id", goal.get("capability_id").deepCopy());
        request.add("parameters", goal.get("parameters").deepCopy());
        return request;
    }
}
