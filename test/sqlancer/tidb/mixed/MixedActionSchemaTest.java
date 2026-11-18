package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class MixedActionSchemaTest {
    static JsonArray corpus() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("docs/data-preparation-v2/real-responses-27.json")))
                .getAsJsonArray();
    }

    private static JsonObject capability(JsonObject state, String id) {
        return state.getAsJsonArray("available_observations").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(item -> item.get("id").getAsString().equals(id)).findFirst().orElseThrow();
    }

    private static JsonArray repairedRequests(JsonArray entries, JsonObject state) {
        JsonArray result = new JsonArray();
        for (JsonElement request : entries) {
            String id = request.isJsonPrimitive() ? request.getAsString()
                    : request.getAsJsonObject().get("capability_id").getAsString();
            JsonObject cap = capability(state, id);
            if (!cap.has("parameters")) {
                result.add(id);
                continue;
            }
            JsonObject object = new JsonObject();
            object.addProperty("capability_id", id);
            JsonObject parameters = request.isJsonObject()
                    ? request.getAsJsonObject().getAsJsonObject("parameters").deepCopy() : new JsonObject();
            for (Map.Entry<String, JsonElement> entry : cap.getAsJsonObject("parameters").entrySet()) {
                JsonObject spec = entry.getValue().getAsJsonObject();
                if (spec.get("kind").getAsString().equals("enum")) {
                    JsonElement value = parameters.get(entry.getKey());
                    parameters.add(entry.getKey(),
                            value != null && value.isJsonObject() ? value.getAsJsonObject().get("value")
                                    : value == null ? spec.getAsJsonArray("values").get(0) : value);
                } else if (!parameters.has(entry.getKey())) {
                    JsonObject value = new JsonObject();
                    value.addProperty("type", "null");
                    value.add("value", JsonNull.INSTANCE);
                    parameters.add(entry.getKey(), value);
                }
            }
            object.add("parameters", parameters);
            result.add(object);
        }
        return result;
    }

    static JsonObject repair(JsonObject plan, JsonObject state) {
        plan = plan.deepCopy();
        if (!plan.get("goal").isJsonPrimitive()) {
            plan.addProperty("goal", plan.get("goal").toString());
        }
        if (plan.get("decision").getAsString().equals("observe")) {
            plan.add("verification_limit", JsonNull.INSTANCE);
        }
        for (String key : List.of("observation_requests", "verification_requests")) {
            plan.add(key, repairedRequests(plan.getAsJsonArray(key), state));
        }
        for (JsonElement operation : plan.getAsJsonArray("operations")) {
            JsonObject object = operation.getAsJsonObject();
            JsonObject table = state.getAsJsonObject("schema").getAsJsonArray("tables").asList().stream()
                    .map(JsonElement::getAsJsonObject).filter(item -> item.get("name").equals(object.get("table")))
                    .findFirst().orElseThrow();
            JsonArray rows = object.getAsJsonArray("rows");
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i).isJsonArray()) {
                    JsonArray values = rows.get(i).getAsJsonArray();
                    JsonObject row = new JsonObject();
                    for (int c = 0; c < values.size(); c++) {
                        row.add(table.getAsJsonArray("columns").get(c).getAsJsonObject().get("name").getAsString(),
                                values.get(c));
                    }
                    rows.set(i, row);
                }
            }
        }
        for (JsonElement goal : plan.getAsJsonArray("structured_goals")) {
            JsonObject object = goal.getAsJsonObject();
            JsonObject cap = capability(state, object.get("capability_id").getAsString());
            if (cap.has("parameters")) {
                JsonArray single = new JsonArray();
                single.add(object.get("capability_id"));
                object.add("parameters", repairedRequests(single, state).get(0).getAsJsonObject().get("parameters"));
            }
        }
        return plan;
    }

    @Test
    void all27RealResponsesHaveLocatedErrorsOrRemainValidAndExplicitRepairsValidate() throws Exception {
        int rejected = 0;
        JsonArray evidence = new JsonArray();
        for (JsonElement entry : corpus()) {
            JsonObject sample = entry.getAsJsonObject();
            JsonObject state = sample.getAsJsonObject("state");
            JsonObject result = new JsonObject();
            result.add("seed", sample.get("seed"));
            try {
                TiDBDataPreparationProtocol.decision(sample.get("response").getAsString(), state);
                assertEquals("accepted", sample.get("original_status").getAsString());
            } catch (TiDBDataPreparationProtocol.ValidationException error) {
                rejected++;
                assertEquals("rejected", sample.get("original_status").getAsString());
                assertTrue(error.details.get("path").getAsString().startsWith("$."));
                assertTrue(error.details.has("expected"));
                assertTrue(error.details.has("received_type"));
                assertTrue(error.details.has("example"));
                result.add("error", error.details);
            }
            JsonObject repaired = repair(JsonParser.parseString(sample.get("response").getAsString()).getAsJsonObject(),
                    state);
            TiDBDataPreparationProtocol.decision(repaired.toString(), state);
            result.add("manual_shape_revision", repaired);
            evidence.add(result);
        }
        assertEquals(22, rejected);
        TiDBMixedSupport.save(Path.of("target/action-interface/corpus-diagnostics.json"), evidence);
    }

    @Test
    void enumObjectErrorIdentifiesExactFieldAndShowsPlainStringExample() throws Exception {
        JsonObject sample = corpus().asList().stream().map(JsonElement::getAsJsonObject)
                .filter(item -> item.get("original_error").isJsonPrimitive()
                        && item.get("original_error").getAsString().contains("unknown parameter choice"))
                .findFirst().orElseThrow();
        TiDBDataPreparationProtocol.ValidationException error = assertThrows(
                TiDBDataPreparationProtocol.ValidationException.class, () -> TiDBDataPreparationProtocol
                        .decision(sample.get("response").getAsString(), sample.getAsJsonObject("state")));
        assertTrue(error.details.get("path").getAsString().endsWith(".parameters.stage"));
        assertEquals("object", error.details.get("received_type").getAsString());
        assertEquals("string", error.details.get("expected").getAsString());
        assertEquals("input", error.details.get("example").getAsString());
    }

    static JsonObject call(String action, JsonObject arguments) {
        JsonObject function = new JsonObject();
        function.addProperty("name", action);
        function.addProperty("arguments", arguments.toString());
        JsonObject item = new JsonObject();
        item.add("function", function);
        JsonArray calls = new JsonArray();
        calls.add(item);
        JsonObject wrapper = new JsonObject();
        wrapper.add("tool_calls", calls);
        return wrapper;
    }

    @Test
    void actionSchemasAreClosedAndCanBeExportedForBetaCompatibilityChecks() throws Exception {
        for (int seed : List.of(42, 49, 51)) {
            JsonObject state = corpus().asList().stream().map(JsonElement::getAsJsonObject)
                    .filter(item -> item.get("seed").getAsInt() == seed).findFirst().orElseThrow()
                    .getAsJsonObject("state");
            JsonArray tools = TiDBMixedActionSchema.tools(state);
            assertEquals(3, tools.size());
            closed(tools);
            TiDBMixedSupport.save(Path.of("target/action-interface/tools-seed" + seed + ".json"), tools);
            TiDBMixedSupport.save(Path.of("target/action-interface/state-seed" + seed + ".json"), state);
        }
    }

    private static void closed(JsonElement schema) {
        if (schema.isJsonObject()) {
            JsonObject obj = schema.getAsJsonObject();
            if (obj.has("properties")) {
                assertFalse(obj.get("additionalProperties").getAsBoolean());
                assertEquals(obj.getAsJsonObject("properties").size(), obj.getAsJsonArray("required").size());
            }
            assertFalse(obj.has("minItems"));
            obj.entrySet().forEach(entry -> closed(entry.getValue()));
        } else if (schema.isJsonArray()) {
            schema.getAsJsonArray().forEach(MixedActionSchemaTest::closed);
        }
    }

    @Test
    void malformedToolEnvelopesAndArgumentsStillReturnLocatedFeedback() throws Exception {
        JsonObject state = corpus().get(0).getAsJsonObject().getAsJsonObject("state");
        for (String value : List.of("{\"tool_calls\":null}", "{\"tool_calls\":[]}", "{\"tool_calls\":[null]}",
                "{\"tool_calls\":[{\"function\":{}}]}",
                "{\"tool_calls\":[{\"function\":{\"name\":\"stop\",\"arguments\":\"[]\"}}]}")) {
            TiDBDataPreparationProtocol.ValidationException error = assertThrows(
                    TiDBDataPreparationProtocol.ValidationException.class,
                    () -> TiDBDataPreparationProtocol.decision(value, state));
            assertTrue(error.details.get("path").getAsString().startsWith("$.tool_calls"));
            assertTrue(error.details.has("example"));
        }
    }

    @Test
    void strictClientSendsThreeFunctionsAndLocallyChecksTheReturnedArguments() throws Exception {
        JsonObject state = corpus().get(0).getAsJsonObject().getAsJsonObject("state");
        JsonObject args = MixedAppendTest
                .json("{\"reason\":\"Enough\",\"evidence_ids\":[],\"stop_reason\":\"Keep current data\"}");
        JsonObject wrapper = call("stop", args);
        TiDBDeepSeekPreparationClient client = new TiDBDeepSeekPreparationClient(body -> {
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();
            assertFalse(request.has("response_format"));
            assertEquals(3, request.getAsJsonArray("tools").size());
            assertEquals("required", request.get("tool_choice").getAsString());
            JsonObject message = wrapper.deepCopy();
            JsonObject choice = new JsonObject();
            choice.addProperty("finish_reason", "tool_calls");
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject response = new JsonObject();
            response.addProperty("id", "scripted");
            response.addProperty("model", "scripted");
            response.add("choices", choices);
            return new TiDBDeepSeekAdvisor.Reply(200, response.toString());
        }, "model", 1, 4000, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true, false);
        String response = client.respond(TiDBDataPreparationProtocol.messages(state.toString()));
        assertEquals("stop", TiDBDataPreparationProtocol.decision(response, state).get("decision").getAsString());
        args.addProperty("sql", "DELETE FROM t0");
        assertThrows(TiDBDataPreparationProtocol.ValidationException.class,
                () -> TiDBDataPreparationProtocol.decision(call("stop", args).toString(), state));
    }
}
