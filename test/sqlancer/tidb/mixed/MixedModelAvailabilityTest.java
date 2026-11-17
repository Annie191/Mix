package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

/** Actual H2 writes/reads with unavailable model transports; no live API or engine claims. */
class MixedModelAvailabilityTest {
    @TempDir
    Path temporary;

    private static TiDBDeepSeekPreparationClient client(TiDBDeepSeekAdvisor.Transport transport) {
        return new TiDBDeepSeekPreparationClient(transport, "fixture", 3, 1000, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO);
    }

    private static String envelope(String content) {
        JsonObject message = new JsonObject();
        message.addProperty("content", content);
        JsonObject choice = new JsonObject();
        choice.addProperty("finish_reason", "stop");
        choice.add("message", message);
        JsonArray choices = new JsonArray();
        choices.add(choice);
        JsonObject result = new JsonObject();
        result.addProperty("id", "availability-fixture");
        result.addProperty("model", "fixture");
        result.add("choices", choices);
        return result.toString();
    }

    private static TiDBMixedPreparationHarness harness(MixedAppendTest.Fixture fixture, Candidate query) {
        return new TiDBMixedPreparationHarness(fixture.state, query, MixedPreparationTest.packet(fixture, query),
                List.of(), 100, 64, 2);
    }

    private static void usable(JsonObject result) {
        assertEquals("model_unavailable", result.get("termination").getAsString());
        assertFalse(result.get("requires_rebuild").getAsBoolean());
        assertTrue(result.getAsJsonObject("final_state").get("frozen").getAsBoolean());
        TiDBAiMixedOracle.requireUsablePreparation(result);
    }

    @Test
    void missingAndBlankCredentialsFreezeWithoutSpendingCallsOrChangingData() throws Exception {
        for (Map<String, String> environment : List.of(Map.<String, String> of(), Map.of("DEEPSEEK_API_KEY", "  "))) {
            try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
                Candidate query = MixedPreparationTest.query(fixture, false);
                TiDBMixedObservations facts = new TiDBMixedObservations(query, "query_loop", 0, 0);
                JsonObject initial = MixedPreparationTest.packet(fixture, query);
                facts.bootstrap(new TiDBMixedSupport(fixture.state), initial.getAsJsonObject("remaining_budget"));
                initial = facts.statePacket("unavailable", "fixed_preparation_case", initial.getAsJsonObject("schema"),
                        new JsonArray(), initial.getAsJsonObject("remaining_budget"));
                JsonArray initialObservations = initial.getAsJsonArray("observations").deepCopy();
                JsonArray initialSamples = initial.getAsJsonArray("samples").deepCopy();
                TiDBMixedPreparationHarness harness = new TiDBMixedPreparationHarness(fixture.state, query, initial,
                        List.of(), 100, 64, 2);
                Map<String, Object> record = new LinkedHashMap<>();
                JsonObject result = TiDBAiMixedOracle.prepareData(harness,
                        () -> TiDBDeepSeekPreparationClient.fromEnvironment(3, environment), record);
                usable(result);
                assertEquals("MISSING_CREDENTIALS", result.getAsJsonObject("model_failure").get("code").getAsString());
                assertEquals("initialization", result.getAsJsonObject("model_failure").get("stage").getAsString());
                assertEquals(initialObservations, result.getAsJsonObject("final_state").getAsJsonArray("observations"));
                assertEquals(initialSamples, result.getAsJsonObject("final_state").getAsJsonArray("samples"));
                assertTrue(result.getAsJsonArray("rounds").isEmpty());
                assertTrue(result.getAsJsonArray("api_audit").isEmpty());
                assertEquals(8, result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget")
                        .get("model_calls").getAsInt());
                assertEquals(0, result.getAsJsonObject("final_state").get("data_revision").getAsInt());
                assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t0"));
                assertTrue(record.containsKey("dataPreparation"));
                assertThrows(IllegalStateException.class, () -> harness.run(messages -> "{}"));
                TiDBMixedSupport.save(Path.of("target/mixed-preparation/model-unavailable.json"), record);
            }
        }
    }

    @Test
    void unreadableCredentialFileAndInvalidConfigurationHaveRedactedReasons() throws Exception {
        for (String code : List.of("CREDENTIALS_UNREADABLE", "INVALID_CLIENT_CONFIGURATION")) {
            Map<String, String> environment = code.equals("CREDENTIALS_UNREADABLE")
                    ? Map.of("DEEPSEEK_API_KEY_FILE", temporary.resolve("sensitive-credential-path").toString())
                    : Map.of("DEEPSEEK_API_KEY", "sensitive-value", "DEEPSEEK_HIT_USD_PER_M", "sensitive-price");
            try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
                Candidate query = MixedPreparationTest.query(fixture, false);
                JsonObject result = harness(fixture, query)
                        .runWithClient(() -> TiDBDeepSeekPreparationClient.fromEnvironment(3, environment));
                usable(result);
                assertEquals(code, result.getAsJsonObject("model_failure").get("code").getAsString());
                assertFalse(result.toString().contains("sensitive-"));
            }
        }
    }

    @Test
    void callFailuresFreezeAndPreserveInitialDataWithoutRetry() throws Exception {
        for (boolean httpError : List.of(false, true)) {
            try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
                Candidate query = MixedPreparationTest.query(fixture, false);
                AtomicInteger calls = new AtomicInteger();
                JsonObject result = harness(fixture, query).runWithClient(() -> client(body -> {
                    calls.incrementAndGet();
                    if (httpError) {
                        return new TiDBDeepSeekAdvisor.Reply(503, "sensitive-response");
                    }
                    throw new IOException("sensitive-transport");
                }));
                usable(result);
                assertEquals(1, calls.get());
                assertEquals("request", result.getAsJsonObject("model_failure").get("stage").getAsString());
                assertEquals(1, result.getAsJsonArray("api_audit").size());
                assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t0"));
                assertEquals("0", fixture.scalar("SELECT COUNT(*) FROM t1"));
                assertEquals(7, result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget")
                        .get("model_calls").getAsInt());
                assertFalse(result.toString().contains("sensitive-"));
            }
        }
    }

    @Test
    void laterModelFailureRetainsCommittedRowsRevisionAndCurrentEvidence() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = harness(fixture, query).runWithClient(() -> client(body -> {
                if (calls.incrementAndGet() > 1) {
                    throw new IOException("later outage");
                }
                JsonObject request = JsonParser.parseString(body).getAsJsonObject();
                JsonObject packet = JsonParser.parseString(
                        request.getAsJsonArray("messages").get(2).getAsJsonObject().get("content").getAsString())
                        .getAsJsonObject();
                return new TiDBDeepSeekAdvisor.Reply(200, envelope(
                        MixedPreparationTest.response(packet, MixedPreparationTest.append(12, true)).toString()));
            }));
            usable(result);
            assertEquals(2, calls.get());
            assertEquals(2, result.getAsJsonArray("api_audit").size());
            JsonObject state = result.getAsJsonObject("final_state");
            assertEquals(1, state.get("data_revision").getAsInt());
            assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t0 WHERE k=12"));
            assertFalse(state.getAsJsonArray("observations").isEmpty());
            assertTrue(state.getAsJsonArray("observations").asList().stream()
                    .allMatch(fact -> fact.getAsJsonObject().get("data_revision").getAsInt() == 1));
            assertEquals("", fixture.snapshot);
            assertEquals("tikv,tiflash", fixture.engines);
        }
    }

    @Test
    void uncertainCommitIsRecordedAndRejectedBeforeAnyDifferentialContinuation() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            fixture.loseCommitReply = true;
            Candidate query = MixedPreparationTest.query(fixture, false);
            AtomicInteger calls = new AtomicInteger();
            Map<String, Object> record = new LinkedHashMap<>();
            TiDBMixedSupport.Rejected rejected = assertThrows(TiDBMixedSupport.Rejected.class,
                    () -> TiDBAiMixedOracle.prepareData(harness(fixture, query), () -> client(body -> {
                        calls.incrementAndGet();
                        JsonObject request = JsonParser.parseString(body).getAsJsonObject();
                        JsonObject packet = JsonParser.parseString(request.getAsJsonArray("messages").get(2)
                                .getAsJsonObject().get("content").getAsString()).getAsJsonObject();
                        return new TiDBDeepSeekAdvisor.Reply(200, envelope(MixedPreparationTest
                                .response(packet, MixedPreparationTest.append(12, true)).toString()));
                    }), record));
            assertEquals("PREPARATION_UNCERTAIN", rejected.status);
            JsonObject result = (JsonObject) record.get("dataPreparation");
            assertTrue(result.get("requires_rebuild").getAsBoolean());
            assertEquals("requires_rebuild", result.get("termination").getAsString());
            assertTrue(result.get("final_state").isJsonNull());
            assertTrue(result.get("model_failure").isJsonNull());
            assertEquals(1, calls.get());
            assertEquals(1, ((JsonArray) record.get("preparationApiAudit")).size());
        }
    }

    @Test
    void databaseObservationFailureEscapesAndStillPreservesApiAudit() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            fixture.failSetup = true;
            Candidate query = MixedPreparationTest.query(fixture, false);
            TiDBMixedPreparationHarness harness = harness(fixture, query);
            assertThrows(java.sql.SQLException.class, () -> harness.runWithClient(() -> client(body -> {
                JsonObject request = JsonParser.parseString(body).getAsJsonObject();
                JsonObject packet = JsonParser.parseString(
                        request.getAsJsonArray("messages").get(2).getAsJsonObject().get("content").getAsString())
                        .getAsJsonObject();
                JsonObject plan = MixedAppendTest.json("{\"decision\":\"observe\",\"reason\":\"inspect\","
                        + "\"goal\":\"count\",\"expected_effect\":\"read only\","
                        + "\"observation_requests\":[\"rows_t0\"]}");
                return new TiDBDeepSeekAdvisor.Reply(200,
                        envelope(MixedPreparationTest.response(packet, plan).toString()));
            })));
            assertEquals(1, harness.apiAudit.size());
            assertThrows(IllegalStateException.class, () -> harness.run(messages -> "{}"));
        }
    }
}
