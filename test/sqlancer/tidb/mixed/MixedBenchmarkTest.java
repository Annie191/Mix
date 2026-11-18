package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import sqlancer.Randomly;
import sqlancer.tidb.mixed.TiDBDeepSeekAdvisor.Reply;

class MixedBenchmarkTest {
    private static final BigDecimal HIT = new BigDecimal("0.003");
    private static final BigDecimal MISS = new BigDecimal("0.15");
    private static final BigDecimal OUTPUT = new BigDecimal("0.60");

    private static JsonObject usage() {
        return MixedAppendTest.json("{\"prompt_tokens\":1000,\"prompt_cache_hit_tokens\":400,"
                + "\"prompt_cache_miss_tokens\":600,\"completion_tokens\":200}");
    }

    private static List<Map<String, String>> messages() throws Exception {
        JsonObject packet = TiDBDataPreparationProtocol
                .state(java.nio.file.Files.readString(java.nio.file.Path.of("docs/data-preparation-v1/01-state.json")));
        return TiDBDataPreparationProtocol.messages(packet.toString());
    }

    private static String envelope(String finish, String content) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("id", "real-response-id");
        envelope.addProperty("model", "returned-model");
        envelope.add("usage", usage());
        JsonObject choice = new JsonObject();
        choice.addProperty("finish_reason", finish);
        choice.add("message", new com.google.gson.Gson().toJsonTree(Map.of("content", content)));
        JsonArray choices = new JsonArray();
        choices.add(choice);
        envelope.add("choices", choices);
        return envelope.toString();
    }

    @Test
    void realPreparationEnvelopeRecordsUsageAndBoundsCallsWithoutSilentFallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        TiDBDeepSeekPreparationClient client = new TiDBDeepSeekPreparationClient(body -> {
            calls.incrementAndGet();
            JsonObject request = MixedAppendTest.json(body);
            assertEquals("json_object", request.getAsJsonObject("response_format").get("type").getAsString());
            assertEquals("disabled", request.getAsJsonObject("thinking").get("type").getAsString());
            assertFalse(request.has("tools"));
            assertEquals(3, request.getAsJsonArray("messages").size());
            return new Reply(200, envelope("stop", "{\"decision\":\"stop\"}"));
        }, "deepseek-flash", 1, 3000, HIT, MISS, OUTPUT);
        assertTrue(client.respond(messages()).contains("stop"));
        assertEquals("0.0002112", client.audit.get(0).getAsJsonObject().get("estimated_usd").getAsBigDecimal()
                .stripTrailingZeros().toPlainString());
        assertEquals("returned-model", client.audit.get(0).getAsJsonObject().get("returned_model").getAsString());
        assertThrows(IllegalArgumentException.class, () -> client.respond(messages()));
        assertEquals(1, calls.get());
    }

    @Test
    void truncationAndWhitespaceStillRecordBilledUsageAndCannotSupplyAFakeDecision() throws Exception {
        for (String finish : List.of("length", "stop")) {
            TiDBDeepSeekPreparationClient client = new TiDBDeepSeekPreparationClient(
                    body -> new Reply(200, envelope(finish, "  ")), "model", 1, 100, HIT, MISS, OUTPUT);
            assertThrows(IllegalArgumentException.class, () -> client.respond(messages()));
            assertTrue(client.audit.get(0).getAsJsonObject().has("usage"));
            assertFalse(client.audit.get(0).getAsJsonObject().get("estimated_usd").isJsonNull());
        }
    }

    @Test
    void httpErrorsAndExceptionsAreRedactedAndNotRetried() throws Exception {
        for (boolean exception : List.of(false, true)) {
            AtomicInteger calls = new AtomicInteger();
            TiDBDeepSeekPreparationClient client = new TiDBDeepSeekPreparationClient(body -> {
                calls.incrementAndGet();
                if (exception) {
                    throw new IOException("Bearer private-key must not be recorded");
                }
                return new Reply(429, "private-key body must not be recorded");
            }, "model", 1, 100, HIT, MISS, OUTPUT);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> client.respond(messages()));
            assertFalse(failure.toString().contains("private-key"));
            assertFalse(client.audit.toString().contains("private-key"));
            assertTrue(client.audit.get(0).getAsJsonObject().get("estimated_usd").isJsonNull());
            assertEquals(1, calls.get());
        }
    }

    @Test
    void pricingUsesReportedCacheCountsAndRejectsInconsistentUsage() {
        JsonObject usage = usage();
        assertEquals(new BigDecimal("0.000211200"), TiDBDeepSeekPreparationClient.cost(usage, HIT, MISS, OUTPUT));
        usage.addProperty("prompt_cache_miss_tokens", 599);
        assertThrows(IllegalArgumentException.class,
                () -> TiDBDeepSeekPreparationClient.cost(usage, HIT, MISS, OUTPUT));
    }

    @Test
    void fixedSeedsProduceTheSameBaselineValuesAndFrozenQueryAcrossArms() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            sqlancer.tidb.TiDBProvider.TiDBGlobalState state = fixture.state;
            // The benchmark's two numeric columns match its controlled corpus.
            List<sqlancer.tidb.TiDBSchema.TiDBTable> tables = List.of(MixedObservationTest.table("t0"),
                    MixedObservationTest.table("t1"));
            for (TiDBDeepSeekAdvisor.Profile profile : List.of(TiDBDeepSeekAdvisor.Profile.RANDOM,
                    TiDBDeepSeekAdvisor.Profile.SHARED)) {
                state.setRandomly(new Randomly(42));
                TiDBMixedGeneration.Candidate first = TiDBMixedPreparationBenchmark.candidate(tables, 42);
                JsonArray left = TiDBMixedPreparationBenchmark.baseline(fixture.state, first, profile, 6);
                state.setRandomly(new Randomly(42));
                TiDBMixedGeneration.Candidate second = TiDBMixedPreparationBenchmark.candidate(tables, 42);
                assertEquals(first.sql, second.sql);
                assertEquals(left, TiDBMixedPreparationBenchmark.baseline(fixture.state, second, profile, 6));
                assertEquals(6,
                        left.asList().stream().mapToInt(o -> o.getAsJsonObject().getAsJsonArray("rows").size()).sum());
            }
        }
    }

    @Test
    void configurableRowsAreSharedAcrossTablesAndBaselineStrategies() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            TiDBMixedGeneration.Candidate query = TiDBMixedPreparationBenchmark
                    .candidate(List.of(MixedObservationTest.table("t0"), MixedObservationTest.table("t1")), 0);
            for (int rows : List.of(0, 1, 6, 7, 12)) {
                assertEquals(rows, TiDBMixedPreparationHarness.preparationBudget(rows).get("insert_rows").getAsInt());
                for (TiDBDeepSeekAdvisor.Profile profile : List.of(TiDBDeepSeekAdvisor.Profile.RANDOM,
                        TiDBDeepSeekAdvisor.Profile.SHARED)) {
                    JsonArray operations = TiDBMixedPreparationBenchmark.baseline(fixture.state, query, profile, rows);
                    assertEquals(rows, operations.asList().stream()
                            .mapToInt(operation -> operation.getAsJsonObject().getAsJsonArray("rows").size()).sum());
                    assertTrue(operations.asList().stream()
                            .allMatch(operation -> !operation.getAsJsonObject().getAsJsonArray("rows").isEmpty()));
                }
            }
            assertThrows(IllegalArgumentException.class, () -> TiDBMixedPreparationHarness.preparationBudget(-1));
            assertThrows(IllegalArgumentException.class, () -> TiDBMixedPreparationHarness.preparationBudget(1001));
        }
    }

    @Test
    void dataCoverageRejectsOldAndSampledFactsAndDoesNotReadEngineStatus() throws Exception {
        JsonObject packet = TiDBDataPreparationProtocol
                .state(java.nio.file.Files.readString(java.nio.file.Path.of("docs/data-preparation-v1/01-state.json")));
        JsonObject fact = MixedAppendTest.json("{\"id\":\"f\",\"capability_id\":\"j0_pairs_after\","
                + "\"query_id\":\"query_1\",\"schema_revision\":0,\"data_revision\":0,\"status\":\"sampled\","
                + "\"value\":{\"count\":\"8\"},\"error\":null}");
        packet.getAsJsonArray("observations").add(fact);
        assertTrue(TiDBMixedDataCoverage.snapshot(packet).getAsJsonArray("present").isEmpty());
        fact.addProperty("status", "exact");
        assertEquals(1, TiDBMixedDataCoverage.snapshot(packet).getAsJsonArray("present").size());
        fact.addProperty("data_revision", 1);
        assertTrue(TiDBMixedDataCoverage.snapshot(packet).getAsJsonArray("present").isEmpty());
    }

    @Test
    void incompleteMeasurementCannotDeclareRowsUselessOrClaimNewlyObservedUnknownBinsAreNew() {
        JsonObject before = MixedAppendTest.json("{\"present\":[\"a\"],\"measured\":[\"a\"]}");
        JsonObject after = MixedAppendTest.json("{\"present\":[\"a\",\"b\"],\"measured\":[\"a\",\"b\"]}");
        JsonObject change = TiDBMixedDataCoverage.difference(before, after);
        assertTrue(change.getAsJsonArray("added").isEmpty());
        assertFalse(change.get("complete").getAsBoolean());
    }

    @Test
    void metricsKeepFailedCasesAndMissingCostWithoutInflatingRates() {
        JsonArray reports = new JsonArray();
        JsonObject report = MixedAppendTest
                .json("{\"strategy\":\"AI\",\"status\":\"ERROR\",\"elapsed_ms\":12,\"api_audit\":[]}");
        JsonObject api = new JsonObject();
        api.add("estimated_usd", JsonNull.INSTANCE);
        report.getAsJsonArray("api_audit").add(api);
        reports.add(report);
        JsonObject metrics = TiDBMixedBenchmarkMetrics.summarize(reports).getAsJsonObject("AI");
        assertEquals(1, metrics.get("cases").getAsInt());
        assertEquals(0, metrics.get("completed_cases").getAsInt());
        assertFalse(metrics.get("cost_complete").getAsBoolean());
        assertTrue(metrics.getAsJsonObject("plan_execution_rate").get("ratio").isJsonNull());
        assertEquals(1, metrics.getAsJsonObject("final_case_goal_rate").get("denominator").getAsInt());
    }

    @Test
    void rejectedAppendIsStillInPlanDenominatorAndCoverageIsIndependentOfEngines() {
        JsonObject report = MixedAppendTest
                .json("{\"strategy\":\"AI\",\"status\":\"COMPLETED\",\"elapsed_ms\":1,\"api_audit\":[],"
                        + "\"preparation\":{\"rounds\":[{\"validation_status\":\"rejected\",\"decision\":null,\"raw_response\":\"{\\\"decision\\\":\\\"append_rows\\\"}\","
                        + "\"writes\":[],\"evaluation\":{\"outcome\":\"unverified\"}}]},"
                        + "\"data_coverage_change\":{\"added\":[\"j0_pairs_after\"],\"lost\":[]},"
                        + "\"engine_outcome\":{\"allPathsValid\":false}} ");
        JsonArray reports = new JsonArray();
        reports.add(report);
        JsonObject metrics = TiDBMixedBenchmarkMetrics.summarize(reports).getAsJsonObject("AI");
        assertEquals(1, metrics.getAsJsonObject("plan_execution_rate").get("denominator").getAsInt());
        assertEquals(0, metrics.getAsJsonObject("plan_execution_rate").get("numerator").getAsInt());
        assertEquals(1, metrics.get("responses_rejected").getAsInt());
        assertEquals(0, metrics.get("new_observed_scene_hits").getAsInt());
        assertEquals(1, metrics.get("legacy_unscoped_scene_hits").getAsInt());
        assertEquals(0, metrics.get("new_effective_scene_hits").getAsInt());
    }

    @Test
    void unmeasuredBeforeAndAfterDoesNotCountSuccessAsNoGain() {
        JsonObject report = MixedAppendTest
                .json("{\"strategy\":\"AI\",\"status\":\"COMPLETED\",\"elapsed_ms\":1,\"api_audit\":[],"
                        + "\"preparation\":{\"rounds\":[{\"validation_status\":\"accepted\",\"decision\":{\"decision\":\"append_rows\"},"
                        + "\"append_feedback\":{\"attempted_rows\":1},\"writes\":[{\"status\":\"success\"}],"
                        + "\"data_coverage_change\":{\"added\":[],\"complete\":false},\"evaluation\":{\"outcome\":\"unverified\"}}]}}");
        JsonArray reports = new JsonArray();
        reports.add(report);
        JsonObject metrics = TiDBMixedBenchmarkMetrics.summarize(reports).getAsJsonObject("AI");
        assertEquals(0, metrics.get("rows_without_new_scene").getAsInt());
        assertEquals(1, metrics.get("rows_gain_unverified").getAsInt());
        assertTrue(metrics.getAsJsonObject("verifiable_append_goal_rate").get("ratio").isJsonNull());
    }

    @Test
    void independentCaseAssessmentClassifiesNoGainWhileRoundBaselineCanRemainUnverified() {
        for (boolean complete : List.of(true, false)) {
            JsonObject report = MixedAppendTest.json("{\"strategy\":\"AI\",\"status\":\"COMPLETED\",\"elapsed_ms\":1,"
                    + "\"api_audit\":[{\"estimated_usd\":0},{\"estimated_usd\":0}],"
                    + "\"preparation\":{\"rounds\":[{\"validation_status\":\"accepted\","
                    + "\"decision\":{\"decision\":\"append_rows\"},\"writes\":[{\"status\":\"success\"}],"
                    + "\"evaluation\":{\"outcome\":\"unverified\"},\"data_coverage_change\":{\"added\":[],\"complete\":false}}]},"
                    + "\"data_coverage_change\":{\"query_stages\":{\"added\":[],\"lost\":[],\"complete\":" + complete
                    + "}}}");
            JsonArray reports = new JsonArray();
            reports.add(report);
            JsonObject metrics = TiDBMixedBenchmarkMetrics.summarize(reports).getAsJsonObject("AI");
            assertEquals(1, metrics.get("rows_gain_unverified").getAsInt());
            assertEquals(complete ? 1 : 0, metrics.get("case_rows_without_new_query_scene").getAsInt());
            assertEquals(complete ? 0 : 1, metrics.get("case_rows_query_gain_unverified").getAsInt());
            assertEquals(0.5,
                    metrics.getAsJsonObject("accepted_response_per_api_call_rate").get("ratio").getAsDouble());
            report.getAsJsonObject("data_coverage_change").getAsJsonObject("query_stages").getAsJsonArray("added")
                    .add("where_boundary_at_before");
            metrics = TiDBMixedBenchmarkMetrics.summarize(reports).getAsJsonObject("AI");
            assertEquals(complete ? 1 : 0, metrics.get("case_rows_with_new_query_scene").getAsInt());
            assertEquals(0, metrics.get("case_rows_without_new_query_scene").getAsInt());
        }
    }

    @Test
    void verificationFloorIsMeasuredAndChargedEvenWhenModelRequestsNoProbes() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            TiDBMixedGeneration.Candidate query = MixedObservationTest.candidate(fixture.tables,
                    sqlancer.tidb.ast.TiDBJoin.JoinType.INNER,
                    sqlancer.tidb.ast.TiDBBinaryComparisonOperation.TiDBComparisonOperator.EQUALS, false);
            TiDBMixedObservations facts = new TiDBMixedObservations(query, "paired_query", 0, 0);
            JsonObject packet = TiDBMixedPreparationBenchmark.packet(facts, fixture.packet.getAsJsonObject("schema"),
                    "floor_test");
            packet.getAsJsonObject("remaining_budget").addProperty("model_calls", 1);
            TiDBMixedPreparationHarness harness = new TiDBMixedPreparationHarness(fixture.state, query, packet,
                    List.of(), 100, 64, 2);
            harness.verificationFloor(List.of("j0_pairs_after"));
            JsonObject result = harness.run(messages -> {
                JsonObject current = com.google.gson.JsonParser.parseString(messages.get(2).get("content"))
                        .getAsJsonObject();
                return TiDBMixedPreparationBenchmark
                        .decision(current, "append_rows", com.google.gson.JsonParser
                                .parseString(MixedAppendTest.operations(MixedAppendTest.row(2))).getAsJsonArray())
                        .toString();
            });
            JsonObject finalState = result.getAsJsonObject("final_state");
            assertEquals(95, finalState.getAsJsonObject("remaining_budget").get("probe_requests").getAsInt());
            assertEquals("exact",
                    finalState.getAsJsonArray("observations").get(0).getAsJsonObject().get("status").getAsString());
            assertThrows(IllegalArgumentException.class, () -> harness.verificationFloor(List.of("j0_pairs_after")));
        }
    }
}
