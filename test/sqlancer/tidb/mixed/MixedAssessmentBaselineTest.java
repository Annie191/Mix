package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

/** Production assessment ordering, independent budgets and non-leaking model input, with actual writes and probes. */
class MixedAssessmentBaselineTest {
    private static TiDBMixedPreparationHarness.ClientFactory scripted(TiDBMixedPreparationHarness.Responder responder) {
        return () -> new TiDBDeepSeekPreparationClient(body -> {
            JsonArray messages = JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("messages");
            List<Map<String, String>> input = messages.asList().stream().map(entry -> {
                JsonObject message = entry.getAsJsonObject();
                return Map.of("role", message.get("role").getAsString(), "content",
                        message.get("content").getAsString());
            }).collect(java.util.stream.Collectors.toList());
            String content;
            try {
                content = responder.respond(input);
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            JsonObject message = new JsonObject();
            message.addProperty("content", content);
            JsonObject choice = new JsonObject();
            choice.addProperty("finish_reason", "stop");
            choice.add("message", message);
            JsonArray choices = new JsonArray();
            choices.add(choice);
            JsonObject response = new JsonObject();
            response.addProperty("id", "assessment-script");
            response.addProperty("model", "fixture");
            response.add("choices", choices);
            response.add("usage", MixedAppendTest.json("{\"prompt_tokens\":0,\"completion_tokens\":0}"));
            return new TiDBDeepSeekAdvisor.Reply(200, response.toString());
        }, "fixture", 3, 3000, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static JsonObject initial(TiDBGlobalState state, TiDBMixedSupport io, Candidate query, JsonObject schema)
            throws Exception {
        JsonObject budget = TiDBMixedPreparationHarness.preparationBudget();
        TiDBMixedObservations facts = new TiDBMixedObservations(query, "production_assessment", 0, 0);
        facts.bootstrap(io, budget);
        return facts.statePacket("assessment", state.getDatabaseName(), schema, new JsonArray(), budget);
    }

    private static JsonObject object(Map<String, Object> report, String field) {
        return (JsonObject) report.get(field);
    }

    private static void samePreparationResources(JsonObject expected, JsonObject actual) {
        for (String field : List.of("model_calls", "probe_requests", "insert_rows")) {
            assertEquals(expected.get(field), actual.get(field));
        }
        // Harness initialization consumes its own elapsed time; assessment does not mutate the input budget.
        assertTrue(actual.get("time_ms").getAsLong() <= expected.get("time_ms").getAsLong());
    }

    static Map<String, Object> matchedScene(TiDBGlobalState state, TiDBMixedSupport io, Candidate query,
            JsonObject schema) throws Exception {
        JsonObject initial = initial(state, io, query, schema);
        JsonObject original = initial.deepCopy();
        Map<String, Object> report = new LinkedHashMap<>();
        AtomicInteger calls = new AtomicInteger();
        TiDBAiMixedOracle.prepareWithAssessment(state, io, query, initial, scripted(messages -> {
            JsonObject packet = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
            if (calls.getAndIncrement() == 0) {
                assertEquals(original.get("observations"), packet.get("observations"));
                assertEquals(original.get("samples"), packet.get("samples"));
                samePreparationResources(original.getAsJsonObject("remaining_budget"),
                        packet.getAsJsonObject("remaining_budget"));
                assertEquals(5, packet.getAsJsonArray("observations").size());
                assertTrue(TiDBMixedDataCoverage.snapshot(packet).getAsJsonArray("measured").isEmpty());
                assertFalse(object(report, "dataCoverageBefore").getAsJsonArray("measured").isEmpty());
                JsonObject plan = MixedPreparationTest.response(packet, MixedPreparationTest.append(12, true));
                plan.getAsJsonArray("operations").add(MixedAppendTest
                        .json("{\"table\":\"t1\",\"rows\":[{\"k\":{\"type\":\"integer\",\"value\":\"12\"}}]}"));
                return plan.toString();
            }
            return MixedPreparationTest.response(packet, MixedPreparationTest.stop()).toString();
        }), report);
        assertEquals(original, initial);
        assertEquals(2, calls.get());
        assertTrue(object(report, "dataCoverageChange").getAsJsonArray("added")
                .contains(new com.google.gson.JsonPrimitive("j0_pairs_after")));
        assertTrue(object(report, "dataCoverageChange").get("complete").getAsBoolean());
        for (String phase : List.of("prePreparationAssessment", "postPreparationAssessment")) {
            Map<?, ?> assessment = (Map<?, ?>) report.get(phase);
            assertEquals("COLLECTED", assessment.get("status"));
            assertEquals(false, assessment.get("model_budget_charged"));
            assertTrue(((Number) assessment.get("elapsed_ms")).longValue() >= 0);
            assertTrue(((Number) assessment.get("probes_attempted")).longValue() > 5);
        }
        assertEquals(0L, ((Map<?, ?>) report.get("prePreparationAssessment")).get("dataRevision"));
        assertEquals(2L, ((Map<?, ?>) report.get("postPreparationAssessment")).get("dataRevision"));
        return report;
    }

    @Test
    void productionMeasuresBeforeWritingWithoutLeakingAssessmentOrSpendingPreparationBudget() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            Map<String, Object> report = matchedScene(fixture.state, new TiDBMixedSupport(fixture.state), query,
                    fixture.packet.getAsJsonObject("schema"));
            assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t0 JOIN t1 ON t0.k=t1.k WHERE t0.k>10"));
            TiDBMixedSupport.save(Path.of("target/mixed-assessment/new-query-scene.json"), report);
        }
    }

    @Test
    void unavailableModelHasComparableBaselineAndNoInventedGain() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            TiDBMixedSupport io = new TiDBMixedSupport(fixture.state);
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject initial = initial(fixture.state, io, query, fixture.packet.getAsJsonObject("schema"));
            Map<String, Object> report = new LinkedHashMap<>();
            JsonObject result = TiDBAiMixedOracle.prepareWithAssessment(fixture.state, io, query, initial,
                    () -> TiDBDeepSeekPreparationClient.fromEnvironment(3, Map.of()), report);
            assertEquals("model_unavailable", result.get("termination").getAsString());
            assertTrue(object(report, "dataCoverageChange").get("complete").getAsBoolean());
            assertTrue(object(report, "dataCoverageChange").getAsJsonArray("added").isEmpty());
            assertTrue(object(report, "dataCoverageChange").getAsJsonArray("lost").isEmpty());
            samePreparationResources(initial.getAsJsonObject("remaining_budget"),
                    result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget"));
            assertEquals(0, result.getAsJsonArray("api_audit").size());
            TiDBMixedSupport.save(Path.of("target/mixed-assessment/unavailable-model.json"), report);
        }
    }

    @Test
    void budgetLimitedBeforeCannotClaimNewlyObservedScenesWereCreated() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            TiDBMixedSupport io = new TiDBMixedSupport(fixture.state);
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject initial = initial(fixture.state, io, query, fixture.packet.getAsJsonObject("schema"));
            Map<String, Object> report = new LinkedHashMap<>();
            JsonObject before = TiDBAiMixedOracle.assessData(io, query, initial, 0, new JsonArray(),
                    new TiDBMixedObservations.Budget(1, 64, 10_000), report, "before");
            JsonObject after = TiDBAiMixedOracle.assessData(io, query, initial, 0, new JsonArray(),
                    new TiDBMixedObservations.Budget(64, 64, 10_000), report, "after");
            JsonObject change = TiDBMixedDataCoverage.difference(TiDBMixedDataCoverage.snapshot(before),
                    TiDBMixedDataCoverage.snapshot(after));
            assertFalse(change.get("complete").getAsBoolean());
            assertTrue(change.getAsJsonArray("added").isEmpty());
            assertFalse(change.getAsJsonArray("unverified").isEmpty());
            assertEquals(1L, ((Map<?, ?>) report.get("before")).get("probes_attempted"));
            assertEquals(0L, ((Map<?, ?>) report.get("before")).get("probes_remaining"));
            assertEquals(91, initial.getAsJsonObject("remaining_budget").get("probe_requests").getAsInt());
        }
    }

    @Test
    void assessmentSessionFailureIsSavedAndPreventsModelCalls() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            TiDBMixedSupport io = new TiDBMixedSupport(fixture.state);
            JsonObject initial = initial(fixture.state, io, query, fixture.packet.getAsJsonObject("schema"));
            fixture.failSetup = true;
            Map<String, Object> report = new LinkedHashMap<>();
            AtomicInteger calls = new AtomicInteger();
            assertThrows(java.sql.SQLException.class,
                    () -> TiDBAiMixedOracle.prepareWithAssessment(fixture.state, io, query, initial, () -> {
                        calls.incrementAndGet();
                        throw new AssertionError("assessment must complete first");
                    }, report));
            assertEquals(0, calls.get());
            assertEquals("ERROR", ((Map<?, ?>) report.get("prePreparationAssessment")).get("status"));
            assertFalse(report.containsKey("dataCoverageChange"));
        }
    }

    @Test
    void uncertainCommitKeepsBeforeEvidenceButNeverMeasuresOrClaimsAfterCoverage() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            TiDBMixedSupport io = new TiDBMixedSupport(fixture.state);
            JsonObject initial = initial(fixture.state, io, query, fixture.packet.getAsJsonObject("schema"));
            fixture.loseCommitReply = true;
            Map<String, Object> report = new LinkedHashMap<>();
            TiDBMixedSupport.Rejected failure = assertThrows(TiDBMixedSupport.Rejected.class, () -> TiDBAiMixedOracle
                    .prepareWithAssessment(fixture.state, io, query, initial, scripted(messages -> {
                        JsonObject packet = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        return MixedPreparationTest.response(packet, MixedPreparationTest.append(12, true)).toString();
                    }), report));
            assertEquals("PREPARATION_UNCERTAIN", failure.status);
            assertFalse(object(report, "dataCoverageBefore").getAsJsonArray("measured").isEmpty());
            assertTrue(object(report, "dataPreparation").get("requires_rebuild").getAsBoolean());
            assertFalse(report.containsKey("postPreparationAssessment"));
            assertFalse(report.containsKey("dataCoverageChange"));
        }
    }
}
