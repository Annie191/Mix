package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.tidb.ast.TiDBAggregate;
import sqlancer.tidb.ast.TiDBAggregate.TiDBAggregateFunction;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation.TiDBComparisonOperator;
import sqlancer.tidb.ast.TiDBBinaryLogicalOperation;
import sqlancer.tidb.ast.TiDBBinaryLogicalOperation.TiDBBinaryLogicalOperator;
import sqlancer.tidb.ast.TiDBColumnReference;
import sqlancer.tidb.ast.TiDBConstant;
import sqlancer.tidb.ast.TiDBExpression;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.ast.TiDBTableReference;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

/** Scripted active decisions, actual bound SELECTs and INSERTs; TiDB session/catalog alone are simulated. */
class MixedActiveObservationTest {
    private static JsonArray array(JsonElement... values) {
        JsonArray array = new JsonArray();
        for (JsonElement value : values) {
            array.add(value);
        }
        return array;
    }

    private static JsonObject request(String id, String parameters) {
        JsonObject request = new JsonObject();
        request.addProperty("capability_id", id);
        request.add("parameters", JsonParser.parseString(parameters));
        return request;
    }

    private static JsonObject key(String id, String stage, long value) {
        return request(id, "{\"stage\":\"" + stage + "\",\"key\":{\"type\":\"integer\",\"value\":\"" + value + "\"}}");
    }

    private static JsonObject goal(JsonObject request, String field, String expected) {
        JsonObject goal = request.deepCopy();
        goal.addProperty("field", field);
        goal.addProperty("comparison", "at_least");
        goal.addProperty("expected", expected);
        return goal;
    }

    private static void execute(MixedAppendTest.Fixture fixture, String sql) throws Exception {
        try (Statement statement = fixture.database.createStatement()) {
            statement.execute(sql);
        }
    }

    private static JsonObject state(MixedAppendTest.Fixture fixture, Candidate query, boolean bootstrap)
            throws Exception {
        TiDBMixedObservations facts = new TiDBMixedObservations(query, "active_query", 0, 0);
        JsonObject budget = TiDBMixedPreparationHarness.preparationBudget();
        if (bootstrap) {
            facts.bootstrap(new TiDBMixedSupport(fixture.state), budget);
        }
        return facts.statePacket("active", "active_observation", fixture.packet.getAsJsonObject("schema"),
                new JsonArray(), budget);
    }

    private static JsonObject observe(JsonObject state, JsonArray requests) {
        JsonObject plan = MixedPreparationTest.response(state, MixedAppendTest.json("{\"decision\":\"observe\","
                + "\"reason\":\"Locate the blocking condition with current exact evidence\",\"goal\":\"Observe first\","
                + "\"expected_effect\":\"Unknown until measured\"}"));
        plan.add("observation_requests", requests);
        return plan;
    }

    private static JsonObject current(List<java.util.Map<String, String>> messages) {
        return JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
    }

    private static JsonObject fact(JsonObject state, JsonElement request) {
        return state.getAsJsonArray("observations").asList().stream().map(JsonElement::getAsJsonObject).filter(
                fact -> TiDBMixedObservationRequest.key(request).equals(TiDBMixedObservationRequest.factKey(fact)))
                .findFirst().orElseThrow();
    }

    private static String scalar(JsonObject fact, String field) {
        assertEquals("exact", fact.get("status").getAsString(), fact.toString());
        JsonObject value = fact.getAsJsonObject("value");
        if (value.has(field)) {
            return value.get(field).getAsString();
        }
        for (int i = 0; i < value.getAsJsonArray("columns").size(); i++) {
            if (field.equals(value.getAsJsonArray("columns").get(i).getAsString())) {
                return value.getAsJsonArray("rows").get(0).getAsJsonArray().get(i).getAsJsonObject().get("value")
                        .getAsString();
            }
        }
        throw new AssertionError("missing field " + field);
    }

    private static JsonObject collect(MixedAppendTest.Fixture fixture, Candidate query, JsonArray requests)
            throws Exception {
        TiDBMixedObservations facts = new TiDBMixedObservations(query, "active_query", 0, 0);
        facts.collectRequests(new TiDBMixedSupport(fixture.state), requests,
                new TiDBMixedObservations.Budget(requests.size(), 64, 30_000));
        return facts.statePacket("active", "active_observation", fixture.packet.getAsJsonObject("schema"),
                new JsonArray(), TiDBMixedPreparationHarness.preparationBudget());
    }

    @Test
    void bootstrapIsSmallChargedAndDoesNotLeakFullQueryDiagnostics() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            execute(fixture, "INSERT INTO t0(k) VALUES(2),(3),(4)");
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = state(fixture, query, true);
            assertEquals(5, packet.getAsJsonArray("observations").size());
            assertEquals(91, packet.getAsJsonObject("remaining_budget").get("probe_requests").getAsInt());
            assertEquals(3, packet.getAsJsonObject("remaining_budget").get("model_calls").getAsInt());
            assertFalse(packet.getAsJsonArray("samples").get(0).getAsJsonObject().get("complete").getAsBoolean());
            assertEquals(2, packet.getAsJsonArray("samples").get(0).getAsJsonObject().getAsJsonArray("rows").size());
            assertTrue(packet.getAsJsonArray("observations").asList().stream().noneMatch(
                    entry -> entry.getAsJsonObject().get("capability_id").getAsString().equals("j0_pairs_after")));
            TiDBMixedObservations facts = new TiDBMixedObservations(query, "active_query", 0, 0);
            assertTrue(facts.available().asList().stream().anyMatch(cap -> cap.getAsJsonObject().has("parameters")));
            assertTrue(
                    TiDBMixedDataCoverage.measurementIds(facts).stream().noneMatch(id -> id.startsWith("key_rows_")));
        }
    }

    @Test
    void parameterizedKeyCountsLocateMissingMatchesAndValuesAreJdbcBindings() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            execute(fixture, "INSERT INTO t0(k) VALUES(10),(12)");
            execute(fixture, "INSERT INTO t1(k) VALUES(12),(12)");
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonArray requests = array(key("key_rows_t0.k", "base", 10), key("key_rows_t0.k", "where_input", 10),
                    key("key_rows_t0.k", "where_input", 12), key("key_matches_j0_t0.k", "local_pairs", 12));
            JsonObject packet = collect(fixture, query, requests);
            assertEquals("1", scalar(fact(packet, requests.get(0)), "count"));
            assertEquals("0", scalar(fact(packet, requests.get(1)), "count"));
            assertEquals("2", scalar(fact(packet, requests.get(2)), "count"));
            assertEquals("2", scalar(fact(packet, requests.get(3)), "count"));
            String malicious = "x' OR 1=1; DELETE FROM t0; --";
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("value", malicious);
            JsonObject parameters = new JsonObject();
            parameters.addProperty("stage", "base");
            parameters.add("key", text);
            JsonObject request = request("key_rows_t0.s", parameters.toString());
            JsonObject result = collect(fixture, query, array(request));
            assertEquals("0", scalar(fact(result, request), "count"));
            assertEquals("3", fixture.scalar("SELECT COUNT(*) FROM t0"));
            assertEquals("", fixture.snapshot);
            assertEquals("tikv,tiflash", fixture.engines);
        }
    }

    @Test
    void predicateSubtreesReportTrueFalseAndNullInsteadOfGuessingFromSamples() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            execute(fixture, "INSERT INTO t0(k) VALUES(10),(12)");
            execute(fixture, "INSERT INTO t1(k) VALUES(10),(12)");
            Candidate query = MixedObservationTest.candidate(fixture.tables, TiDBJoin.JoinType.LEFT,
                    TiDBComparisonOperator.EQUALS, false);
            TiDBExpression predicate = new TiDBBinaryComparisonOperation(
                    new TiDBColumnReference(fixture.tables.get(1).getColumns().get(0)),
                    TiDBConstant.createIntConstant(10), TiDBComparisonOperator.GREATER);
            query.ast.setWhereClause(new TiDBBinaryLogicalOperation(predicate, TiDBConstant.createBooleanConstant(true),
                    TiDBBinaryLogicalOperator.OR));
            query = new Candidate(query.ast, query.tables, List.of());
            JsonObject input = request("predicate_where_l", "{\"stage\":\"input\"}");
            JsonObject output = request("predicate_where", "{\"stage\":\"output\"}");
            JsonObject packet = collect(fixture, query, array(input, output));
            assertEquals("1", scalar(fact(packet, input), "true_count"));
            assertEquals("1", scalar(fact(packet, input), "false_count"));
            assertEquals("1", scalar(fact(packet, input), "null_count"));
            assertEquals("3", scalar(fact(packet, output), "true_count"));
        }
    }

    @Test
    void compositeNonEqualityOnAndSpecificGroupsUseOriginalAst() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            execute(fixture, "INSERT INTO t0(k) VALUES(2)");
            execute(fixture, "INSERT INTO t1(k) VALUES(1),(2),(2),(NULL)");
            Candidate query = MixedObservationTest.candidate(fixture.tables, TiDBJoin.JoinType.INNER,
                    TiDBComparisonOperator.GREATER_EQUALS, false);
            TiDBJoin join = (TiDBJoin) query.ast.getJoinList().get(0);
            TiDBExpression left = new TiDBColumnReference(fixture.tables.get(0).getColumns().get(0));
            join.setOnCondition(
                    new TiDBBinaryLogicalOperation(
                            join.getOnCondition(), new TiDBBinaryComparisonOperation(left,
                                    TiDBConstant.createIntConstant(1), TiDBComparisonOperator.GREATER),
                            TiDBBinaryLogicalOperator.AND));
            query.ast.setGroupByExpressions(List.of(left));
            query.ast.setFetchColumns(List.of(left,
                    new TiDBAggregate(List.of(TiDBConstant.createIntConstant(1)), TiDBAggregateFunction.COUNT)));
            query = new Candidate(query.ast, query.tables, List.of());
            JsonObject on = request("predicate_j0_on", "{\"stage\":\"input\"}");
            JsonObject group = request("group_input_rows", "{\"key0\":{\"type\":\"integer\",\"value\":\"2\"}}");
            JsonObject matches = key("key_matches_j0_t0.k", "local_pairs", 2);
            JsonObject packet = collect(fixture, query, array(on, group, matches));
            assertEquals("3", scalar(fact(packet, on), "true_count"));
            assertEquals("4", scalar(fact(packet, on), "false_count"));
            assertEquals("1", scalar(fact(packet, on), "null_count"));
            assertEquals("3", scalar(fact(packet, group), "count"));
            assertEquals("3", scalar(fact(packet, matches), "count"));
            JsonObject invalid = observe(packet, array(request("predicate_j0_on", "{\"stage\":\"output\"}")));
            assertThrows(IllegalArgumentException.class,
                    () -> TiDBDataPreparationProtocol.decision(invalid.toString(), packet));
        }
    }

    @Test
    void parameterizedGoalsDoNotUseEvidenceForADifferentKeyOrStage() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            execute(fixture, "INSERT INTO t1(k) VALUES(1),(1)");
            Candidate query = MixedObservationTest.candidate(fixture.tables, TiDBJoin.JoinType.INNER,
                    TiDBComparisonOperator.EQUALS, false);
            JsonObject one = key("key_rows_t0.k", "where_input", 1);
            JsonObject two = key("key_rows_t0.k", "where_input", 2);
            JsonObject packet = collect(fixture, query, array(one));
            assertEquals("achieved", TiDBMixedPreparationGoals.evaluate(packet, array(goal(one, "count", "2")), false)
                    .get("outcome").getAsString());
            assertEquals("unverified", TiDBMixedPreparationGoals.evaluate(packet, array(goal(two, "count", "1")), false)
                    .get("outcome").getAsString());
            JsonObject base = key("key_rows_t0.k", "base", 1);
            assertEquals("unverified", TiDBMixedPreparationGoals
                    .evaluate(packet, array(goal(base, "count", "1")), false).get("outcome").getAsString());
        }
    }

    @Test
    void partialObserveRetainsUnrelatedFactsSamplesAndParameterVariantsButReplacesRefreshedEvidence() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject initial = state(fixture, query, true);
            JsonElement rows = new com.google.gson.JsonPrimitive("rows_t0");
            String oldId = fact(initial, rows).get("id").getAsString();
            JsonObject keyOne = key("key_rows_t0.k", "base", 1);
            JsonObject keyTwo = key("key_rows_t0.k", "base", 2);
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, initial, List.of(), 100, 64, 2)
                    .run(messages -> {
                        JsonObject packet = current(messages);
                        switch (calls.getAndIncrement()) {
                        case 0:
                            return observe(packet, array(keyOne)).toString();
                        case 1:
                            assertEquals(oldId, fact(packet, rows).get("id").getAsString());
                            assertEquals(initial.get("samples"), packet.get("samples"));
                            return observe(packet, array(rows, keyTwo)).toString();
                        default:
                            assertFalse(oldId.equals(fact(packet, rows).get("id").getAsString()));
                            assertEquals("1", scalar(fact(packet, keyOne), "count"));
                            assertEquals("0", scalar(fact(packet, keyTwo), "count"));
                            return MixedPreparationTest.response(packet, MixedPreparationTest.stop()).toString();
                        }
                    });
            JsonObject packet = result.getAsJsonObject("final_state");
            assertEquals(7, packet.getAsJsonArray("observations").size());
            assertEquals(88, packet.getAsJsonObject("remaining_budget").get("probe_requests").getAsInt());
        }
    }

    @Test
    void sameParameterRefreshErrorsRemoveOldExactProofButRetainOtherFacts() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject key = key("key_rows_t0.k", "base", 1);
            JsonObject initial = collect(fixture, query, array(key, new com.google.gson.JsonPrimitive("rows_t0")));
            fixture.insertWarning = true;
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, initial, List.of(), 100, 64, 2)
                    .run(messages -> calls.getAndIncrement() == 0 ? observe(current(messages), array(key)).toString()
                            : MixedPreparationTest.response(current(messages), MixedPreparationTest.stop()).toString());
            JsonObject packet = result.getAsJsonObject("final_state");
            assertEquals("error", fact(packet, key).get("status").getAsString());
            assertEquals("1", scalar(fact(packet, new com.google.gson.JsonPrimitive("rows_t0")), "count"));
            assertEquals("unverified", TiDBMixedPreparationGoals.evaluate(packet, array(goal(key, "count", "1")), false)
                    .get("outcome").getAsString());
            assertEquals(2, packet.getAsJsonArray("observations").size());
        }
    }

    @Test
    void invalidParametersSqlTargetsDuplicatesAndOverspendingAreRejectedBeforeReads() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = state(fixture, query, true);
            JsonObject valid = key("key_rows_t0.k", "base", 1);
            for (String invalid : List.of("unknown_id", "stage", "key_type", "missing", "sql", "duplicate", "budget")) {
                JsonObject request = valid.deepCopy();
                JsonArray requests = array(request);
                JsonObject state = packet.deepCopy();
                switch (invalid) {
                case "unknown_id":
                    request.addProperty("capability_id", "SELECT * FROM t0");
                    break;
                case "stage":
                    request.getAsJsonObject("parameters").addProperty("stage", "SQL");
                    break;
                case "key_type":
                    request.getAsJsonObject("parameters").addProperty("key", "1 OR 1=1");
                    break;
                case "missing":
                    request.getAsJsonObject("parameters").remove("key");
                    break;
                case "sql":
                    request.addProperty("sql", "SELECT * FROM t0");
                    break;
                case "duplicate":
                    requests.add(request.deepCopy());
                    break;
                default:
                    state.getAsJsonObject("remaining_budget").addProperty("probe_requests", 0);
                    break;
                }
                JsonObject plan = observe(state, requests);
                assertThrows(IllegalArgumentException.class,
                        () -> TiDBDataPreparationProtocol.decision(plan.toString(), state), invalid);
            }
        }
    }

    @Test
    void observeAppendStopLoopFindsBlockingConditionAndInvalidatesFactsAfterActualWrites() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            execute(fixture, "INSERT INTO t1(k) VALUES(1)");
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject initial = state(fixture, query, true);
            JsonObject key = key("key_rows_t0.k", "where_output", 12);
            JsonObject predicate = request("predicate_where", "{\"stage\":\"input\"}");
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, initial, List.of(), 100, 64, 2)
                    .run(messages -> {
                        JsonObject packet = current(messages);
                        int call = calls.getAndIncrement();
                        if (call == 0) {
                            return observe(packet, array(predicate, key)).toString();
                        }
                        if (call == 1) {
                            assertEquals("1", scalar(fact(packet, predicate), "false_count"));
                            assertEquals("0", scalar(fact(packet, key), "count"));
                            JsonObject plan = MixedPreparationTest.response(packet,
                                    MixedPreparationTest.append(12, false));
                            JsonArray operations = plan.getAsJsonArray("operations");
                            operations.add(MixedAppendTest.json(
                                    "{\"table\":\"t1\",\"rows\":[{\"k\":{\"type\":\"integer\",\"value\":\"12\"}}]}"));
                            plan.add("structured_goals", array(goal(key, "count", "1")));
                            return plan.toString();
                        }
                        assertEquals("1", scalar(fact(packet, key), "count"));
                        assertEquals(1, packet.getAsJsonArray("observations").size());
                        assertTrue(packet.getAsJsonArray("samples").isEmpty());
                        return MixedPreparationTest.response(packet, MixedPreparationTest.stop()).toString();
                    });
            assertEquals(3, calls.get());
            assertEquals("achieved", result.getAsJsonObject("model_goal_evaluation").get("outcome").getAsString());
            assertEquals(88, result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget")
                    .get("probe_requests").getAsInt());
            TiDBMixedSupport.save(Path.of("target/mixed-preparation/active-observe-loop.json"), result);
        }
    }

    @Test
    void threeTableDiagnosticsDistinguishLocalPairsFromWholeQueryRows() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            execute(fixture, "CREATE TABLE t2(k INT)");
            execute(fixture, "INSERT INTO t1(k) VALUES(1),(1)");
            sqlancer.tidb.TiDBSchema.TiDBTable third = MixedObservationTest.table("t2");
            Candidate query = MixedPreparationTest.query(fixture, false);
            query.ast.setWhereClause(null);
            query.ast.setJoinList(List.of(new TiDBJoin(query.ast.getJoinList().get(0), new TiDBTableReference(third),
                    TiDBJoin.JoinType.INNER,
                    new TiDBBinaryComparisonOperation(
                            new TiDBColumnReference(fixture.tables.get(0).getColumns().get(0)),
                            new TiDBColumnReference(third.getColumns().get(0)), TiDBComparisonOperator.EQUALS))));
            query = new Candidate(query.ast, List.of(fixture.tables.get(0), fixture.tables.get(1), third), List.of());
            JsonObject local = key("key_matches_j1_t0.k", "local_pairs", 1);
            JsonObject global = key("key_matches_j1_t0.k", "query_input", 1);
            TiDBMixedObservations facts = new TiDBMixedObservations(query, "three_tables", 0, 0);
            facts.collectRequests(new TiDBMixedSupport(fixture.state), array(local, global),
                    new TiDBMixedObservations.Budget(2, 64, 30_000));
            JsonObject packet = new JsonObject();
            packet.add("observations", facts.observations);
            assertEquals("2", scalar(fact(packet, local), "count"));
            assertEquals("0", scalar(fact(packet, global), "count"));
        }
    }

    @Test
    void nullCompositeGroupKeysAndPreciseDecimalsAreBoundWithoutPrecisionLoss() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            execute(fixture, "UPDATE t0 SET n=123456789012345678901234567890123456.12 WHERE k=1");
            Candidate query = MixedObservationTest.candidate(fixture.tables, TiDBJoin.JoinType.LEFT,
                    TiDBComparisonOperator.EQUALS, false);
            query.ast.setGroupByExpressions(List.of(new TiDBColumnReference(fixture.tables.get(0).getColumns().get(0)),
                    new TiDBColumnReference(fixture.tables.get(1).getColumns().get(0))));
            query.ast.setFetchColumns(List
                    .of(new TiDBAggregate(List.of(TiDBConstant.createIntConstant(1)), TiDBAggregateFunction.COUNT)));
            query = new Candidate(query.ast, query.tables, List.of());
            JsonObject group = request("group_input_rows",
                    "{\"key0\":{\"type\":\"integer\",\"value\":\"1\"},\"key1\":{\"type\":\"null\",\"value\":null}}");
            JsonObject decimal = request("key_rows_t0.n",
                    "{\"stage\":\"base\",\"key\":{\"type\":\"decimal\",\"value\":\"123456789012345678901234567890123456.12\"}}");
            JsonObject packet = collect(fixture, query, array(group, decimal));
            assertEquals("1", scalar(fact(packet, group), "count"));
            assertEquals("1", scalar(fact(packet, decimal), "count"));
            JsonObject equivalent = request("key_rows_t0.n",
                    "{\"key\":{\"value\":\"123456789012345678901234567890123456.1200\",\"type\":\"decimal\"},\"stage\":\"base\"}");
            assertEquals(TiDBMixedObservationRequest.key(decimal), TiDBMixedObservationRequest.key(equivalent));
            assertEquals("achieved", TiDBMixedPreparationGoals
                    .evaluate(packet, array(goal(equivalent, "count", "1")), false).get("outcome").getAsString());
        }
    }

    @Test
    void failedInsertWithoutDataChangeKeepsUnrelatedCurrentEvidence() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject initial = state(fixture, query, true);
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, initial, List.of(), 100, 64, 2)
                    .run(messages -> {
                        JsonObject packet = current(messages);
                        if (calls.getAndIncrement() == 0) {
                            JsonObject plan = MixedPreparationTest.response(packet,
                                    MixedPreparationTest.append(1, false));
                            plan.add("verification_requests", array(new com.google.gson.JsonPrimitive("rows_t0")));
                            return plan.toString();
                        }
                        assertEquals(initial.get("samples"), packet.get("samples"));
                        assertEquals(0, packet.get("data_revision").getAsInt());
                        assertEquals(5, packet.getAsJsonArray("observations").size());
                        return MixedPreparationTest.response(packet, MixedPreparationTest.stop()).toString();
                    });
            assertEquals("failed", result.getAsJsonArray("rounds").get(0).getAsJsonObject().getAsJsonArray("writes")
                    .get(0).getAsJsonObject().get("status").getAsString());
            assertEquals(initial.get("samples"), result.getAsJsonObject("final_state").get("samples"));
        }
    }

    @Test
    void finalAssessmentRemeasuresTheExactModelSelectedParametersWithinItsBudget() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject key = key("key_rows_t0.k", "base", 1);
            JsonArray goals = array(goal(key, "count", "1"));
            TiDBMixedObservations facts = new TiDBMixedObservations(query, "active_query", 0, 0);
            JsonArray requests = facts.assessmentRequests(goals);
            assertEquals(TiDBMixedObservationRequest.key(key), TiDBMixedObservationRequest.key(requests.get(0)));
            facts.collectRequests(new TiDBMixedSupport(fixture.state), requests,
                    new TiDBMixedObservations.Budget(1, 64, 30_000));
            JsonObject packet = facts.statePacket("assessment", "active_observation",
                    fixture.packet.getAsJsonObject("schema"), new JsonArray(),
                    TiDBMixedPreparationHarness.preparationBudget());
            assertEquals("achieved",
                    TiDBMixedPreparationGoals.evaluate(packet, goals, false).get("outcome").getAsString());
            assertEquals("unknown",
                    fact(packet, new com.google.gson.JsonPrimitive("rows_t0")).get("status").getAsString());
        }
    }
}
