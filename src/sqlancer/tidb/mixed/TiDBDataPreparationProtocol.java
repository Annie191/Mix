package sqlancer.tidb.mixed;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

/** Stage-one wire contract only: no HTTP, JDBC, model scheduling, or execution of proposed operations. */
public final class TiDBDataPreparationProtocol {
    public static final String VERSION = "1.0";
    private static final Set<String> STATE_FIELDS = Set.of("protocol_version", "request_id", "case_id", "query_id",
            "schema_revision", "data_revision", "phase", "frozen", "discrepancy_found", "query", "schema",
            "observations", "samples", "history", "remaining_budget", "available_observations");
    private static final Set<String> DECISION_FIELDS = Set.of("protocol_version", "request_id", "case_id", "query_id",
            "schema_revision", "data_revision", "decision", "reason", "evidence_ids", "goal", "observation_requests",
            "operations", "expected_effect", "verification_requests", "verification_limit", "stop_reason");
    private static final Set<String> VALUE_KINDS = Set.of("integer", "decimal", "text", "boolean", "binary", "null");

    public static final class ValidationException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        public final String code;

        private ValidationException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    private TiDBDataPreparationProtocol() {
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static JsonObject object(JsonElement value) {
        require(value != null && value.isJsonObject(), "expected object");
        return value.getAsJsonObject();
    }

    private static JsonArray array(JsonElement value) {
        require(value != null && value.isJsonArray(), "expected array");
        return value.getAsJsonArray();
    }

    private static String string(JsonElement value) {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "expected string");
        return value.getAsString();
    }

    private static String text(JsonElement value) {
        String result = string(value);
        require(!result.isBlank(), "expected nonempty text");
        return result;
    }

    private static boolean bool(JsonElement value) {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean(), "expected boolean");
        return value.getAsBoolean();
    }

    private static long integer(JsonElement value) {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "expected integer");
        try {
            long result = value.getAsBigDecimal().longValueExact();
            require(result >= 0, "negative version or budget");
            return result;
        } catch (ArithmeticException error) {
            throw new IllegalArgumentException("integer outside protocol range", error);
        }
    }

    private static void fields(JsonObject obj, Set<String> expected) {
        if (!obj.keySet().equals(expected)) {
            throw new ValidationException("INVALID_FIELDS", "missing or unexpected fields: " + obj.keySet());
        }
    }

    private static void nullableText(JsonElement value) {
        require(value != null, "missing nullable text");
        if (!value.isJsonNull()) {
            text(value);
        }
    }

    private static Set<String> strings(JsonElement value) {
        Set<String> result = new HashSet<>();
        for (JsonElement item : array(value)) {
            require(result.add(text(item)), "duplicate array entry");
        }
        return result;
    }

    private static JsonElement read(JsonReader reader, int depth) throws IOException {
        require(depth <= 40, "JSON nesting exceeds protocol limit");
        switch (reader.peek()) {
        case BEGIN_OBJECT:
            JsonObject obj = new JsonObject();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                require(!obj.has(key), "duplicate JSON field: " + key);
                obj.add(key, read(reader, depth + 1));
            }
            reader.endObject();
            return obj;
        case BEGIN_ARRAY:
            JsonArray arr = new JsonArray();
            reader.beginArray();
            while (reader.hasNext()) {
                arr.add(read(reader, depth + 1));
            }
            reader.endArray();
            return arr;
        case STRING:
            return new JsonPrimitive(reader.nextString());
        case NUMBER:
            return new JsonPrimitive(new BigDecimal(reader.nextString()));
        case BOOLEAN:
            return new JsonPrimitive(reader.nextBoolean());
        case NULL:
            reader.nextNull();
            return JsonNull.INSTANCE;
        default:
            throw new IllegalArgumentException("invalid JSON token");
        }
    }

    private static JsonObject parse(String json, int limit) {
        require(json != null && json.length() <= limit, "JSON exceeds protocol limit");
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            JsonObject result = object(read(reader, 0));
            require(reader.peek() == JsonToken.END_DOCUMENT, "trailing JSON content");
            return result;
        } catch (IOException | NumberFormatException error) {
            throw new IllegalArgumentException("invalid protocol JSON", error);
        }
    }

    private static void typedValue(JsonElement element) {
        JsonObject cell = object(element);
        fields(cell, Set.of("type", "value"));
        String type = text(cell.get("type"));
        require(VALUE_KINDS.contains(type), "unknown value type");
        JsonElement value = cell.get("value");
        switch (type) {
        case "null":
            require(value.isJsonNull(), "null value must be JSON null");
            break;
        case "boolean":
            bool(value);
            break;
        case "integer":
            String whole = string(value);
            require(whole.matches("-?(0|[1-9][0-9]*)"), "integer requires decimal string");
            new BigInteger(whole);
            break;
        case "decimal":
            String decimal = string(value);
            require(decimal.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?"), "decimal requires plain decimal string");
            new BigDecimal(decimal);
            break;
        case "binary":
            String encoded = string(value);
            require(Base64.getEncoder().encodeToString(Base64.getDecoder().decode(encoded)).equals(encoded),
                    "binary requires canonical Base64");
            break;
        default:
            string(value); // Text is data, even when it contains SQL-looking strings.
            break;
        }
    }

    private static Map<String, JsonObject> named(JsonArray entries, String name) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement entry : entries) {
            JsonObject obj = object(entry);
            require(result.put(text(obj.get(name)), obj) == null, "duplicate identifier");
        }
        return result;
    }

    private static void evidenceVersion(JsonObject entry) {
        text(entry.get("query_id"));
        integer(entry.get("schema_revision"));
        integer(entry.get("data_revision"));
    }

    public static JsonObject state(String json) {
        JsonObject state = parse(json, 128_000);
        fields(state, STATE_FIELDS);
        require(VERSION.equals(text(state.get("protocol_version"))), "unsupported protocol version");
        for (String key : List.of("request_id", "case_id", "query_id")) {
            text(state.get(key));
        }
        evidenceVersion(state);
        require("DATA_PREPARATION".equals(text(state.get("phase"))), "wrong phase");
        bool(state.get("frozen"));
        bool(state.get("discrepancy_found"));
        JsonObject query = object(state.get("query"));
        fields(query, Set.of("sql", "joins", "where", "group_by", "having"));
        text(query.get("sql"));
        named(array(query.get("joins")), "id");
        nullableText(query.get("where"));
        array(query.get("group_by"));
        nullableText(query.get("having"));
        JsonObject schema = object(state.get("schema"));
        fields(schema, Set.of("id", "complete", "tables"));
        text(schema.get("id"));
        bool(schema.get("complete"));
        Map<String, JsonObject> tables = named(array(schema.get("tables")), "name");
        require(!tables.isEmpty(), "empty query schema");
        for (JsonObject table : tables.values()) {
            fields(table, Set.of("name", "metadata_complete", "columns", "constraints"));
            bool(table.get("metadata_complete"));
            Map<String, JsonObject> columns = named(array(table.get("columns")), "name");
            require(!columns.isEmpty(), "empty column metadata");
            for (JsonObject column : columns.values()) {
                fields(column, Set.of("name", "sql_type", "value_kind", "nullable", "generated",
                        "generation_expression", "auto_increment", "default_expression"));
                text(column.get("sql_type"));
                require(VALUE_KINDS.contains(text(column.get("value_kind")))
                        && !"null".equals(column.get("value_kind").getAsString()), "invalid column value kind");
                bool(column.get("nullable"));
                bool(column.get("generated"));
                bool(column.get("auto_increment"));
                nullableText(column.get("generation_expression"));
                if (!column.get("default_expression").isJsonNull()) {
                    string(column.get("default_expression")); // Empty/whitespace defaults are actual catalog data.
                }
            }
            array(table.get("constraints")); // Constraint acquisition/semantics belong to stage two.
        }
        Set<String> identifiers = new HashSet<>();
        require(identifiers.add(text(state.get("query_id"))), "duplicate evidence identifier");
        require(identifiers.add(text(schema.get("id"))), "duplicate evidence identifier");
        for (JsonElement element : array(state.get("observations"))) {
            JsonObject observation = object(element);
            fields(observation, Set.of("id", "capability_id", "query_id", "schema_revision", "data_revision", "status",
                    "value", "error"));
            require(identifiers.add(text(observation.get("id"))), "duplicate evidence identifier");
            text(observation.get("capability_id"));
            evidenceVersion(observation);
            require(Set.of("exact", "sampled", "unknown", "unsupported", "error")
                    .contains(text(observation.get("status"))), "unknown observation status");
            nullableText(observation.get("error"));
        }
        for (JsonElement element : array(state.get("samples"))) {
            JsonObject sample = object(element);
            fields(sample, Set.of("id", "table", "query_id", "schema_revision", "data_revision", "columns", "rows",
                    "complete"));
            require(identifiers.add(text(sample.get("id"))), "duplicate evidence identifier");
            evidenceVersion(sample);
            bool(sample.get("complete"));
            JsonObject table = tables.get(text(sample.get("table")));
            require(table != null, "unknown sample table");
            Set<String> columns = strings(sample.get("columns"));
            require(!columns.isEmpty() && named(array(table.get("columns")), "name").keySet().containsAll(columns),
                    "unknown sample columns");
            for (JsonElement row : array(sample.get("rows"))) {
                require(array(row).size() == columns.size(), "sample width mismatch");
                row.getAsJsonArray().forEach(TiDBDataPreparationProtocol::typedValue);
            }
        }
        array(state.get("history")).forEach(TiDBDataPreparationProtocol::object);
        JsonObject budget = object(state.get("remaining_budget"));
        fields(budget, Set.of("model_calls", "probe_requests", "insert_rows", "time_ms"));
        budget.entrySet().forEach(entry -> integer(entry.getValue()));
        for (JsonObject capability : named(array(state.get("available_observations")), "id").values()) {
            fields(capability, Set.of("id", "metric", "target", "description"));
            text(capability.get("metric"));
            object(capability.get("target"));
            text(capability.get("description"));
        }
        return state;
    }

    private static String resource(String name) {
        try (InputStream input = TiDBDataPreparationProtocol.class.getResourceAsStream("protocol/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing protocol prompt: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("cannot read protocol prompt", error);
        }
    }

    public static List<Map<String, String>> messages(String stateJson) {
        JsonObject state = state(stateJson);
        require(integer(object(state.get("remaining_budget")).get("model_calls")) > 0, "no model-call budget");
        return List.of(Map.of("role", "system", "content", resource("overall-v1.txt")),
                Map.of("role", "system", "content", resource("data-preparation-v1.txt")),
                Map.of("role", "user", "content", state.toString()));
    }

    private static void requests(JsonElement entries, Set<String> capabilities, long budget) {
        Set<String> ids = strings(entries);
        if (ids.size() > budget || !capabilities.containsAll(ids)) {
            throw new ValidationException("INVALID_OBSERVATION_REQUESTS",
                    "unknown or over-budget observation requests; allowed IDs: " + capabilities + "; limit: " + budget);
        }
    }

    public static JsonObject decision(String json, JsonObject input) {
        JsonObject state = state(input.toString()); // Revalidate even if the caller modified an earlier state object.
        JsonObject decision = parse(json, 32_000);
        fields(decision, DECISION_FIELDS);
        for (String key : List.of("protocol_version", "request_id", "case_id", "query_id")) {
            require(text(decision.get(key)).equals(text(state.get(key))), "stale or mismatched identity: " + key);
        }
        for (String key : List.of("schema_revision", "data_revision")) {
            require(integer(decision.get(key)) == integer(state.get(key)), "stale version: " + key);
        }
        String action = text(decision.get("decision"));
        require(Set.of("observe", "append_rows", "stop").contains(action), "unauthorized decision");
        for (String key : List.of("reason", "goal", "expected_effect")) {
            text(decision.get(key));
        }
        nullableText(decision.get("verification_limit"));
        nullableText(decision.get("stop_reason"));
        Set<String> evidence = new HashSet<>();
        evidence.add(text(state.get("query_id")));
        evidence.add(text(object(state.get("schema")).get("id")));
        named(array(state.get("observations")), "id").keySet().forEach(evidence::add);
        named(array(state.get("samples")), "id").keySet().forEach(evidence::add);
        if (!evidence.containsAll(strings(decision.get("evidence_ids")))) {
            throw new ValidationException("UNKNOWN_EVIDENCE_ID", "invented evidence identifier");
        }
        Set<String> capabilities = named(array(state.get("available_observations")), "id").keySet();
        JsonObject budget = object(state.get("remaining_budget"));
        requests(decision.get("observation_requests"), capabilities, integer(budget.get("probe_requests")));
        requests(decision.get("verification_requests"), capabilities, integer(budget.get("probe_requests")));
        JsonArray operations = array(decision.get("operations"));
        if (action.equals("stop")) {
            text(decision.get("stop_reason"));
            require(operations.isEmpty() && array(decision.get("observation_requests")).isEmpty()
                    && array(decision.get("verification_requests")).isEmpty(), "stop cannot request execution");
            return decision;
        }
        require(!bool(state.get("frozen")) && !bool(state.get("discrepancy_found"))
                && integer(budget.get("time_ms")) > 0 && integer(budget.get("model_calls")) > 0,
                "only stop is allowed after freeze, discrepancy, or budget exhaustion");
        require(decision.get("stop_reason").isJsonNull(), "non-stop decision has stop reason");
        if (action.equals("observe")) {
            require(!array(decision.get("observation_requests")).isEmpty() && operations.isEmpty()
                    && array(decision.get("verification_requests")).isEmpty()
                    && decision.get("verification_limit").isJsonNull(), "invalid observe payload");
        } else {
            require(array(decision.get("observation_requests")).isEmpty() && !operations.isEmpty(),
                    "invalid append payload");
            require(!array(decision.get("verification_requests")).isEmpty()
                    || !decision.get("verification_limit").isJsonNull(), "missing verification or unverified reason");
            Set<String> cited = strings(decision.get("evidence_ids"));
            for (String collection : List.of("observations", "samples")) {
                for (JsonElement entry : array(state.get(collection))) {
                    JsonObject fact = object(entry);
                    if (cited.contains(text(fact.get("id")))) {
                        require(text(fact.get("query_id")).equals(text(state.get("query_id")))
                                && integer(fact.get("schema_revision")) == integer(state.get("schema_revision"))
                                && integer(fact.get("data_revision")) == integer(state.get("data_revision")),
                                "append cannot cite stale evidence; observe first");
                    }
                }
            }
            validateRows(operations, state, integer(budget.get("insert_rows")));
        }
        return decision;
    }

    private static void validateRows(JsonArray operations, JsonObject state, long rowBudget) {
        Map<String, JsonObject> tables = named(array(object(state.get("schema")).get("tables")), "name");
        Set<String> selected = new HashSet<>();
        long count = 0;
        for (JsonElement entry : operations) {
            JsonObject operation = object(entry);
            fields(operation, Set.of("table", "rows"));
            String tableName = text(operation.get("table"));
            require(selected.add(tableName) && tables.containsKey(tableName), "unknown or repeated operation table");
            Map<String, JsonObject> columns = named(array(tables.get(tableName).get("columns")), "name");
            JsonArray rows = array(operation.get("rows"));
            require(!rows.isEmpty(), "empty append operation");
            for (JsonElement entryRow : rows) {
                JsonObject row = object(entryRow);
                require(!row.keySet().isEmpty(), "row must specify at least one column");
                for (Map.Entry<String, JsonElement> cell : row.entrySet()) {
                    JsonObject column = columns.get(cell.getKey());
                    require(column != null && !bool(column.get("generated")), "unknown or generated write column");
                    typedValue(cell.getValue());
                    String type = text(object(cell.getValue()).get("type"));
                    require(type.equals("null") ? bool(column.get("nullable"))
                            : type.equals(text(column.get("value_kind"))), "column/value kind mismatch");
                }
                if (++count > rowBudget) {
                    throw new ValidationException("APPEND_ROW_BUDGET_EXCEEDED",
                            "append exceeds remaining row budget; total across all tables must be <= " + rowBudget);
                }
            }
        }
    }
}
