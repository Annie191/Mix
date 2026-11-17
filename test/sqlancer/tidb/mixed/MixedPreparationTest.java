package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.tidb.ast.TiDBBinaryComparisonOperation;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation.TiDBComparisonOperator;
import sqlancer.tidb.ast.TiDBColumnReference;
import sqlancer.tidb.ast.TiDBConstant;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;
import sqlancer.tidb.mixed.TiDBMixedPreparationHarness.Requirement;

/** Scripted responses exercise the real protocol, INSERT executor, probe SQL and evidence evaluator. */
class MixedPreparationTest {
    private static final List<Requirement> DUPLICATES = List.of(Requirement.count("j0_pairs_after", 2, true),
            Requirement.count("j0_duplicate_after", 1, true));

    static JsonObject response(JsonObject packet, JsonObject script) {
        JsonObject plan = new JsonObject();
        for (String key : List.of("protocol_version", "request_id", "case_id", "query_id", "schema_revision",
                "data_revision")) {
            plan.add(key, packet.get(key));
        }
        for (String key : List.of("observation_requests", "operations", "verification_requests", "evidence_ids",
                "structured_goals")) {
            plan.add(key, new JsonArray());
        }
        for (String key : List.of("verification_limit", "stop_reason")) {
            plan.add(key, JsonNull.INSTANCE);
        }
        script.entrySet().forEach(entry -> plan.add(entry.getKey(), entry.getValue().deepCopy()));
        // Cite actual current state, not IDs invented in a template.
        JsonArray cited = plan.getAsJsonArray("evidence_ids");
        cited.add(packet.getAsJsonObject("schema").get("id"));
        packet.getAsJsonArray("observations").forEach(entry -> cited.add(entry.getAsJsonObject().get("id")));
        return plan;
    }

    static Candidate query(MixedAppendTest.Fixture fixture, boolean alwaysEmpty) {
        Candidate candidate = MixedObservationTest.candidate(fixture.tables, TiDBJoin.JoinType.INNER,
                TiDBComparisonOperator.EQUALS, false);
        candidate.ast.setWhereClause(alwaysEmpty ? TiDBConstant.createBooleanConstant(false)
                : new TiDBBinaryComparisonOperation(new TiDBColumnReference(fixture.tables.get(0).getColumns().get(0)),
                        TiDBConstant.createIntConstant(10), TiDBComparisonOperator.GREATER));
        return new Candidate(candidate.ast, fixture.tables, List.of());
    }

    static JsonObject packet(MixedAppendTest.Fixture fixture, Candidate candidate) {
        TiDBMixedObservations facts = new TiDBMixedObservations(candidate, "query_loop", 0, 0);
        JsonObject budget = MixedAppendTest
                .json("{\"model_calls\":8,\"probe_requests\":40,\"insert_rows\":8,\"time_ms\":60000}");
        return facts.statePacket("mock", "fixed_preparation_case", fixture.packet.getAsJsonObject("schema"),
                new JsonArray(), budget);
    }

    private static JsonObject round(JsonObject result, int index) {
        return result.getAsJsonArray("rounds").get(index).getAsJsonObject();
    }

    private static String outcome(JsonObject result) {
        return result.getAsJsonObject("evaluation").get("outcome").getAsString();
    }

    private static String count(JsonObject round, String capability) {
        return round.getAsJsonArray("actual_observations").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(o -> o.get("capability_id").getAsString().equals(capability)).findFirst().orElseThrow()
                .getAsJsonObject("value").get("count").getAsString();
    }

    private static JsonObject run(MixedAppendTest.Fixture fixture, String name, boolean empty, List<Requirement> goals,
            int sampleRows) throws Exception {
        Candidate query = query(fixture, empty);
        JsonObject packet = packet(fixture, query);
        JsonArray script = JsonParser
                .parseString(Files.readString(Path.of("docs/data-preparation-loop", name + ".json"))).getAsJsonArray();
        AtomicInteger cursor = new AtomicInteger();
        TiDBMixedPreparationHarness harness = new TiDBMixedPreparationHarness(fixture.state, query, packet, goals, 100,
                sampleRows, 2);
        JsonObject result = harness.run(messages -> {
            assertEquals(List.of("system", "system", "user"),
                    messages.stream().map(m -> m.get("role")).collect(java.util.stream.Collectors.toList()));
            JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
            assertEquals(cursor.get(), current.getAsJsonArray("history").size());
            return response(current, script.get(cursor.getAndIncrement()).getAsJsonObject()).toString();
        });
        TiDBMixedSupport.save(Path.of("target/mixed-preparation", name + ".json"), result);
        assertEquals("", fixture.snapshot);
        assertEquals("tikv,tiflash", fixture.engines);
        assertThrows(IllegalStateException.class, () -> harness.run(messages -> "{}"));
        return result;
    }

    private static void insert(MixedAppendTest.Fixture fixture, String sql) throws Exception {
        try (Statement statement = fixture.database.createStatement()) {
            statement.execute(sql);
        }
    }

    @Test
    void filteredSharedValuesAreRepairedAndMeasuredBeforeDeclaringCoverage() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            insert(fixture, "INSERT INTO t1(k) VALUES(1)");
            JsonObject result = run(fixture, "filtered_shared", false, DUPLICATES, 64);
            assertEquals("1", count(round(result, 0), "j0_pairs_before"));
            assertEquals("0", count(round(result, 0), "j0_pairs_after"));
            assertEquals("not_achieved", round(result, 0).getAsJsonObject("evaluation").get("outcome").getAsString());
            assertEquals(3, round(result, 1).getAsJsonArray("writes").size());
            assertEquals("2", count(round(result, 1), "j0_pairs_after"));
            assertEquals("1", count(round(result, 1), "j0_duplicate_after"));
            assertEquals("achieved", outcome(result));
            assertEquals(3, result.getAsJsonObject("final_state").get("data_revision").getAsInt());
        }
    }

    @Test
    void uniqueConstraintFailureLeadsToADifferentTableAndRealVerification() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            insert(fixture, "INSERT INTO t0(k) VALUES(12)");
            insert(fixture, "INSERT INTO t1(k) VALUES(12)");
            JsonObject result = run(fixture, "unique_blocks_duplicates", false, DUPLICATES, 64);
            assertEquals("failed",
                    round(result, 1).getAsJsonArray("writes").get(0).getAsJsonObject().get("status").getAsString());
            assertEquals("not_achieved", round(result, 1).getAsJsonObject("evaluation").get("outcome").getAsString());
            assertEquals("success",
                    round(result, 2).getAsJsonArray("writes").get(0).getAsJsonObject().get("status").getAsString());
            assertEquals("achieved", outcome(result));
            assertEquals("2", fixture.scalar("SELECT COUNT(*) FROM t0"));
            assertEquals("original", fixture.scalar("SELECT s FROM t0 WHERE k=1"));
        }
    }

    @Test
    void sufficientExistingCoverageStopsWithoutAnyInsert() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            insert(fixture, "INSERT INTO t0(k) VALUES(12)");
            insert(fixture, "INSERT INTO t1(k) VALUES(12),(12)");
            JsonObject result = run(fixture, "already_sufficient", false, DUPLICATES, 64);
            assertEquals("achieved", outcome(result));
            assertTrue(result.getAsJsonArray("rounds").asList().stream()
                    .allMatch(r -> r.getAsJsonObject().getAsJsonArray("writes").isEmpty()));
            assertEquals(8, result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget").get("insert_rows")
                    .getAsInt());
        }
    }

    @Test
    void samplesAloneCannotProveCoverageAndNeedNotCauseWrites() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            insert(fixture, "INSERT INTO t0(k) VALUES(12)");
            insert(fixture, "INSERT INTO t1(k) VALUES(1),(12),(12)");
            JsonObject result = run(fixture, "samples_only", false, DUPLICATES, 1);
            assertEquals("unverified", outcome(result));
            assertTrue(round(result, 0).getAsJsonArray("actual_samples").asList().stream()
                    .noneMatch(s -> s.getAsJsonObject().get("complete").getAsBoolean()));
            assertTrue(round(result, 1).getAsJsonArray("writes").isEmpty());
            assertEquals("0", result.getAsJsonObject("final_state").get("data_revision").getAsString());
        }
    }

    @Test
    void consecutiveDifferentConstraintFailuresTerminateWithoutUnboundedRetries() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            insert(fixture, "INSERT INTO t0(k) VALUES(12)");
            JsonObject result = run(fixture, "consecutive_constraint_failures", false, DUPLICATES, 64);
            assertEquals("no_progress_limit", result.get("termination").getAsString());
            assertEquals(2, result.getAsJsonArray("rounds").size());
            assertEquals("not_achieved", outcome(result));
            assertEquals("0", result.getAsJsonObject("final_state").get("data_revision").getAsString());
            assertEquals(6, result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget").get("insert_rows")
                    .getAsInt());
        }
    }

    @Test
    void meaningfulEmptyResultIsAnExplicitGoalAndIsPreserved() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            insert(fixture, "INSERT INTO t1(k) VALUES(1)");
            JsonObject result = run(fixture, "meaningful_empty", true,
                    List.of(Requirement.empty(true), Requirement.count("j0_pairs_after", 0, false)), 64);
            assertEquals("achieved", outcome(result));
            assertEquals("1", count(round(result, 0), "j0_pairs_before"));
            assertEquals("0", count(round(result, 0), "j0_pairs_after"));
            assertTrue(round(result, 1).getAsJsonArray("writes").isEmpty());
            assertEquals("model_stop", result.get("termination").getAsString());
        }
    }

    static JsonObject append(long key, boolean verify) {
        return MixedAppendTest.json("{\"decision\":\"append_rows\",\"reason\":\"negative scripted assertion\","
                + "\"goal\":\"duplicate coverage\",\"expected_effect\":\"I claim coverage already succeeded\","
                + "\"operations\":" + MixedAppendTest.operations(MixedAppendTest.row(key))
                + ",\"verification_requests\":" + (verify ? "[\"j0_pairs_after\"]" : "[]") + ",\"verification_limit\":"
                + (verify ? "null" : "\"not measured\"") + "}");
    }

    static JsonObject stop() {
        return MixedAppendTest.json("{\"decision\":\"stop\",\"reason\":\"finished\",\"goal\":\"preserve data\","
                + "\"expected_effect\":\"no writes\",\"stop_reason\":\"finished\"}");
    }

    @Test
    void rejectedSevenAndEightRowPlansCanBeRevisedWithinTheSameSixRowBudget() throws Exception {
        JsonArray scripts = JsonParser
                .parseString(Files.readString(Path.of("docs/data-preparation-loop/filtered_shared.json")))
                .getAsJsonArray();
        for (int proposedRows : List.of(7, 8)) {
            try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
                Candidate query = query(fixture, false);
                JsonObject packet = packet(fixture, query);
                packet.add("remaining_budget", TiDBMixedPreparationHarness.preparationBudget(6));
                packet.getAsJsonArray("observations").add(MixedAppendTest
                        .json("{\"id\":\"existing_rows\",\"capability_id\":\"rows_t0\",\"query_id\":\"query_loop\","
                                + "\"schema_revision\":0,\"data_revision\":0,\"status\":\"exact\",\"value\":{\"count\":\"1\"},\"error\":null}"));
                AtomicInteger calls = new AtomicInteger();
                JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, DUPLICATES, 100, 64,
                        2).run(messages -> {
                            JsonObject current = JsonParser.parseString(messages.get(2).get("content"))
                                    .getAsJsonObject();
                            int index = calls.getAndIncrement();
                            if (index == 0) {
                                JsonObject script = append(12, true);
                                JsonArray rows = script.getAsJsonArray("operations").get(0).getAsJsonObject()
                                        .getAsJsonArray("rows");
                                for (int i = 1; i < proposedRows; i++) {
                                    rows.add(MixedAppendTest.json(MixedAppendTest.row(12 + i)));
                                }
                                return response(current, script).toString();
                            }
                            if (index == 1) {
                                JsonObject feedback = current.getAsJsonArray("history").get(0).getAsJsonObject()
                                        .getAsJsonObject("validation_feedback");
                                assertEquals("APPEND_ROW_BUDGET_EXCEEDED", feedback.get("code").getAsString());
                                assertTrue(feedback.get("recoverable").getAsBoolean());
                                assertTrue(feedback.get("retry_allowed").getAsBoolean());
                                assertFalse(feedback.get("execution_started").getAsBoolean());
                                assertEquals(0, feedback.get("attempted_rows").getAsInt());
                                assertEquals(0, current.get("data_revision").getAsInt());
                                assertEquals(packet.get("observations"), current.get("observations"));
                                assertEquals(6,
                                        current.getAsJsonObject("remaining_budget").get("insert_rows").getAsInt());
                                assertEquals(2,
                                        current.getAsJsonObject("remaining_budget").get("model_calls").getAsInt());
                                return response(current, scripts.get(1).getAsJsonObject()).toString();
                            }
                            return response(current, stop()).toString();
                        });
                assertEquals(3, calls.get());
                assertEquals("model_stop", result.get("termination").getAsString());
                assertTrue(round(result, 0).getAsJsonArray("writes").isEmpty());
                assertEquals(3, round(result, 1).getAsJsonArray("writes").size());
                assertEquals("2", count(round(result, 1), "j0_pairs_after"));
                assertEquals("achieved", outcome(result));
                JsonObject remaining = result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget");
                assertEquals(0, remaining.get("model_calls").getAsInt());
                assertEquals(3, remaining.get("insert_rows").getAsInt());
                assertEquals(3, result.getAsJsonObject("final_state").get("data_revision").getAsInt());
                TiDBMixedSupport.save(Path.of("target/mixed-preparation", "rejected_" + proposedRows + "_rows.json"),
                        result);
            }
        }
    }

    @Test
    void badFieldsObservationIdsEvidenceAndVersionsCanBeCorrected() throws Exception {
        for (String error : List.of("field", "missing_field", "observation", "evidence", "request", "version",
                "json")) {
            try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
                Candidate query = query(fixture, false);
                JsonObject packet = packet(fixture, query);
                packet.add("remaining_budget", TiDBMixedPreparationHarness.preparationBudget(6));
                AtomicInteger calls = new AtomicInteger();
                JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, DUPLICATES, 100, 64,
                        2).run(messages -> {
                            JsonObject current = JsonParser.parseString(messages.get(2).get("content"))
                                    .getAsJsonObject();
                            int index = calls.getAndIncrement();
                            if (index == 0) {
                                JsonObject bad = response(current, append(12, true));
                                switch (error) {
                                case "field":
                                    bad.addProperty("unexpected", true);
                                    break;
                                case "missing_field":
                                    bad.remove("expected_effect");
                                    break;
                                case "observation":
                                    bad.getAsJsonArray("verification_requests").add("invented_probe");
                                    break;
                                case "evidence":
                                    bad.getAsJsonArray("evidence_ids").add("invented_fact");
                                    break;
                                case "request":
                                    bad.addProperty("request_id", "stale_request");
                                    break;
                                case "version":
                                    bad.addProperty("data_revision", 99);
                                    break;
                                default:
                                    return "{broken json";
                                }
                                return bad.toString();
                            }
                            if (index == 1) {
                                JsonObject previous = current.getAsJsonArray("history").get(0).getAsJsonObject();
                                assertTrue(previous.getAsJsonObject("validation_feedback").get("retry_allowed")
                                        .getAsBoolean());
                                assertEquals(0, current.get("data_revision").getAsInt());
                                assertEquals(packet.get("observations"), current.get("observations"));
                                return response(current, append(12, true)).toString();
                            }
                            return response(current, stop()).toString();
                        });
                assertEquals(3, calls.get(), error);
                assertEquals("rejected", round(result, 0).get("validation_status").getAsString());
                assertEquals("accepted", round(result, 1).get("validation_status").getAsString());
                assertEquals(1, result.getAsJsonObject("final_state").get("data_revision").getAsInt());
                assertEquals("model_stop", result.get("termination").getAsString());
            }
        }
    }

    @Test
    void repeatedInvalidPlansConsumeCallsAndTimeButNoRowOrProbeBudget() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = query(fixture, false);
            JsonObject packet = packet(fixture, query);
            packet.add("remaining_budget", TiDBMixedPreparationHarness.preparationBudget(6));
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, DUPLICATES, 100, 64, 2)
                    .run(messages -> {
                        calls.incrementAndGet();
                        return "{}";
                    });
            assertEquals(3, calls.get());
            assertEquals("budget_exhausted", result.get("termination").getAsString());
            JsonObject remaining = result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget");
            assertEquals(0, remaining.get("model_calls").getAsInt());
            assertEquals(6, remaining.get("insert_rows").getAsInt());
            assertEquals(96, remaining.get("probe_requests").getAsInt());
            assertTrue(remaining.get("time_ms").getAsLong() < 120_000);
            assertFalse(round(result, 2).getAsJsonObject("validation_feedback").get("retry_allowed").getAsBoolean());
            assertTrue(result.getAsJsonArray("rounds").asList().stream()
                    .allMatch(turn -> turn.getAsJsonObject().getAsJsonArray("writes").isEmpty()));
        }
    }

    @Test
    void transportFailureStopsImmediatelyWithoutLeakingItsMessageOrRetrying() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = query(fixture, false);
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet(fixture, query),
                    DUPLICATES, 100, 64, 2).run(messages -> {
                        calls.incrementAndGet();
                        throw new java.io.IOException("sensitive transport detail");
                    });
            assertEquals(1, calls.get());
            assertEquals("model_unavailable", result.get("termination").getAsString());
            assertEquals("MODEL_REQUEST_FAILED",
                    round(result, 0).getAsJsonObject("validation_feedback").get("code").getAsString());
            assertFalse(round(result, 0).getAsJsonObject("validation_feedback").get("recoverable").getAsBoolean());
            assertFalse(result.toString().contains("sensitive transport detail"));
        }
    }

    @Test
    void expiredTimeBudgetPreventsAnotherModelCallAfterARecoverableRejection() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = query(fixture, false);
            JsonObject packet = packet(fixture, query);
            packet.add("remaining_budget", TiDBMixedPreparationHarness.preparationBudget(6));
            packet.getAsJsonObject("remaining_budget").addProperty("time_ms", 1);
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, DUPLICATES, 100, 64, 2)
                    .run(messages -> {
                        calls.incrementAndGet();
                        Thread.sleep(20);
                        return "{}";
                    });
            assertEquals(1, calls.get());
            assertEquals("budget_exhausted", result.get("termination").getAsString());
            assertTrue(round(result, 0).getAsJsonObject("validation_feedback").get("recoverable").getAsBoolean());
            assertFalse(round(result, 0).getAsJsonObject("validation_feedback").get("retry_allowed").getAsBoolean());
            assertEquals(0, result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget").get("time_ms")
                    .getAsInt());
        }
    }

    @Test
    void modelCanChooseObservationThenStopAfterARejectedPlan() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = query(fixture, false);
            JsonObject packet = packet(fixture, query);
            packet.add("remaining_budget", TiDBMixedPreparationHarness.preparationBudget(6));
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, DUPLICATES, 100, 64, 2)
                    .run(messages -> {
                        JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        int index = calls.getAndIncrement();
                        if (index == 0) {
                            return "{}";
                        }
                        if (index == 1) {
                            return response(current,
                                    MixedAppendTest.json("{\"decision\":\"observe\",\"reason\":\"check first\","
                                            + "\"goal\":\"measure\",\"expected_effect\":\"unknown\",\"observation_requests\":[\"j0_pairs_after\"]}"))
                                                    .toString();
                        }
                        return response(current, stop()).toString();
                    });
            assertEquals(3, calls.get());
            assertEquals("model_stop", result.get("termination").getAsString());
            assertEquals("0", count(round(result, 1), "j0_pairs_after"));
            assertEquals(0, result.getAsJsonObject("final_state").get("data_revision").getAsInt());
            assertTrue(result.getAsJsonArray("rounds").asList().stream()
                    .allMatch(turn -> turn.getAsJsonObject().getAsJsonArray("writes").isEmpty()));
        }
    }

    @Test
    void frozenDataAndDiscoveredDiscrepancyDoNotCallTheModel() throws Exception {
        for (String field : List.of("frozen", "discrepancy_found")) {
            try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
                Candidate query = query(fixture, false);
                JsonObject packet = packet(fixture, query);
                packet.addProperty(field, true);
                JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, DUPLICATES, 100, 64,
                        2).run(messages -> {
                            throw new AssertionError("closed preparation must not request a model response");
                        });
                assertEquals("preparation_closed", result.get("termination").getAsString());
                assertTrue(result.getAsJsonArray("rounds").isEmpty());
            }
        }
    }

    @Test
    void verificationFloorRejectionCanBeRepairedBeforeAnyWrite() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = query(fixture, false);
            JsonObject packet = packet(fixture, query);
            packet.add("remaining_budget", TiDBMixedPreparationHarness.preparationBudget(6));
            packet.getAsJsonObject("remaining_budget").addProperty("probe_requests", 1);
            TiDBMixedPreparationHarness harness = new TiDBMixedPreparationHarness(fixture.state, query, packet,
                    DUPLICATES, 100, 64, 2);
            harness.verificationFloor(List.of("rows_t0"));
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = harness.run(messages -> {
                JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                int index = calls.getAndIncrement();
                if (index == 2) {
                    return response(current, stop()).toString();
                }
                JsonObject plan = response(current, append(12, true));
                if (index == 1) {
                    plan.getAsJsonArray("verification_requests").set(0, new com.google.gson.JsonPrimitive("rows_t0"));
                    assertEquals(0, current.get("data_revision").getAsInt());
                    assertEquals(1, current.getAsJsonObject("remaining_budget").get("probe_requests").getAsInt());
                }
                return plan.toString();
            });
            assertEquals(3, calls.get());
            assertTrue(round(result, 0).getAsJsonArray("writes").isEmpty());
            assertTrue(round(result, 0).get("failure_reason").getAsString().contains("verification floor"));
            assertEquals(1, round(result, 1).getAsJsonArray("writes").size());
            assertEquals("unverified", outcome(result));
        }
    }

    @Test
    void successfulWriteAndConfidentModelExplanationCannotProveCoverage() throws Exception {
        for (boolean verify : List.of(false, true)) {
            try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
                Candidate query = query(fixture, false);
                JsonObject packet = packet(fixture, query);
                packet.getAsJsonObject("remaining_budget").addProperty("model_calls", 1);
                JsonObject result = new TiDBMixedPreparationHarness(
                        fixture.state, query, packet, DUPLICATES, 100, 64, 2)
                                .run(messages -> response(
                                        JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject(),
                                        append(12, verify)).toString());
                assertEquals("success",
                        round(result, 0).getAsJsonArray("writes").get(0).getAsJsonObject().get("status").getAsString());
                assertEquals(verify ? "not_achieved" : "unverified", outcome(result));
                assertFalse(outcome(result).equals("achieved"));
            }
        }
    }

    @Test
    void repeatedFailedOperationIsRejectedDespiteNewReasonAndFreshObservationIds() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = query(fixture, false);
            JsonObject packet = packet(fixture, query);
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, DUPLICATES, 100, 64, 3)
                    .run(messages -> {
                        JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        JsonObject script = append(1, true);
                        script.addProperty("reason", "Claim new reason " + calls.incrementAndGet());
                        if (calls.get() > 1) {
                            script.getAsJsonArray("operations").get(0).getAsJsonObject().getAsJsonArray("rows").get(0)
                                    .getAsJsonObject().getAsJsonObject("n").addProperty("value", "12.340");
                        }
                        return response(current, script).toString();
                    });
            assertEquals("response_rejected", result.get("termination").getAsString());
            assertTrue(round(result, 1).getAsJsonArray("writes").isEmpty());
            assertTrue(round(result, 1).get("failure_reason").getAsString().contains("repeated failed row"));
            assertEquals(7, result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget").get("insert_rows")
                    .getAsInt());
            assertEquals(2, calls.get());
        }
    }

    @Test
    void staleAndSampledAndUnsupportedFactsCannotSatisfyRequirements() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            JsonObject packet = packet(fixture, query(fixture, false));
            JsonObject observation = MixedAppendTest.json("{\"id\":\"e1\",\"capability_id\":\"j0_pairs_after\","
                    + "\"query_id\":\"query_loop\",\"schema_revision\":0,\"data_revision\":0,"
                    + "\"status\":\"exact\",\"value\":{\"count\":\"8\"},\"error\":null}");
            packet.getAsJsonArray("observations").add(observation);
            for (String status : List.of("sampled", "unknown", "unsupported", "error")) {
                observation.addProperty("status", status);
                assertEquals("unverified",
                        TiDBMixedPreparationHarness
                                .evaluate(packet, List.of(Requirement.count("j0_pairs_after", 1, true))).get("outcome")
                                .getAsString());
            }
            observation.addProperty("status", "exact");
            observation.addProperty("data_revision", 9);
            assertEquals("unverified",
                    TiDBMixedPreparationHarness.evaluate(packet, List.of(Requirement.count("j0_pairs_after", 1, true)))
                            .get("outcome").getAsString());
            observation.addProperty("data_revision", 0);
            for (String key : List.of("query_id", "schema_revision")) {
                JsonElement before = observation.get(key);
                observation.add(key, key.equals("query_id") ? new com.google.gson.JsonPrimitive("other_query")
                        : new com.google.gson.JsonPrimitive(9));
                assertEquals("unverified",
                        TiDBMixedPreparationHarness
                                .evaluate(packet, List.of(Requirement.count("j0_pairs_after", 1, true))).get("outcome")
                                .getAsString());
                observation.add(key, before);
            }
        }
    }

    @Test
    void unknownCommitOutcomeCannotKeepEarlierCoverageOrRunVerification() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            fixture.loseCommitReply = true;
            Candidate query = query(fixture, false);
            JsonObject packet = packet(fixture, query);
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, DUPLICATES, 100, 64, 2)
                    .run(messages -> {
                        calls.incrementAndGet();
                        JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        return response(current, append(12, true)).toString();
                    });
            assertEquals("requires_rebuild", result.get("termination").getAsString());
            assertEquals(1, calls.get());
            assertEquals("unknown",
                    round(result, 0).getAsJsonArray("writes").get(0).getAsJsonObject().get("status").getAsString());
            assertEquals("unverified", outcome(result));
            assertTrue(result.get("final_state").isJsonNull());
            assertTrue(round(result, 0).getAsJsonArray("actual_observations").isEmpty());
        }
    }

    @Test
    void emptyRequirementsAreNotVacuouslyAchievedAndNoCallBudgetMeansNoWork() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = query(fixture, true);
            JsonObject packet = packet(fixture, query);
            assertEquals("unverified",
                    TiDBMixedPreparationHarness.evaluate(packet, List.of()).get("outcome").getAsString());
            packet.getAsJsonObject("remaining_budget").addProperty("model_calls", 0);
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 64, 2)
                    .run(messages -> {
                        throw new AssertionError("model must not be called without budget");
                    });
            assertEquals("budget_exhausted", result.get("termination").getAsString());
            assertEquals("unverified", outcome(result));
            assertTrue(result.getAsJsonArray("rounds").isEmpty());
            assertTrue(result.getAsJsonObject("final_state").get("frozen").getAsBoolean());
        }
    }
}
