package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
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
import sqlancer.tidb.ast.TiDBCase;
import sqlancer.tidb.ast.TiDBColumnReference;
import sqlancer.tidb.ast.TiDBConstant;
import sqlancer.tidb.ast.TiDBExpression;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.ast.TiDBTableReference;
import sqlancer.tidb.ast.TiDBUnaryPostfixOperation;
import sqlancer.tidb.ast.TiDBUnaryPostfixOperation.TiDBUnaryPostfixOperator;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

/** Real H2 relational probes; model prose and base-table values cannot create operator-stage credit. */
class MixedQueryStageCoverageTest {
    private static void execute(MixedAppendTest.Fixture fixture, String sql) throws Exception {
        try (Statement statement = fixture.database.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Candidate query(MixedAppendTest.Fixture fixture, TiDBJoin.JoinType type, TiDBExpression where) {
        Candidate candidate = MixedObservationTest.candidate(fixture.tables, type, TiDBComparisonOperator.EQUALS,
                false);
        candidate.ast.setWhereClause(where);
        return new Candidate(candidate.ast, candidate.tables, List.of());
    }

    private static TiDBExpression comparison(MixedAppendTest.Fixture fixture, int table, long bound) {
        return new TiDBBinaryComparisonOperation(new TiDBColumnReference(fixture.tables.get(table).getColumns().get(0)),
                TiDBConstant.createIntConstant(bound), TiDBComparisonOperator.GREATER);
    }

    private static JsonObject packet(MixedAppendTest.Fixture fixture, Candidate query, long revision) throws Exception {
        TiDBMixedObservations facts = new TiDBMixedObservations(query, "query_stages", 0, revision);
        facts.collect(new TiDBMixedSupport(fixture.state), facts.allIds(),
                new TiDBMixedObservations.Budget(200, 64, 60_000));
        return facts.statePacket("stages", "coverage", fixture.packet.getAsJsonObject("schema"), new JsonArray(),
                TiDBMixedPreparationHarness.preparationBudget());
    }

    private static JsonObject fact(JsonObject packet, String id) {
        return packet.getAsJsonArray("observations").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(fact -> fact.get("capability_id").getAsString().equals(id)).findFirst().orElseThrow();
    }

    private static String count(JsonObject packet, String id) {
        JsonObject fact = fact(packet, id);
        assertEquals("exact", fact.get("status").getAsString(), fact.toString());
        return fact.getAsJsonObject("value").get("count").getAsString();
    }

    private static boolean present(JsonObject coverage, String scope, String bucket) {
        return coverage.getAsJsonObject(scope).getAsJsonArray("present").asList().stream()
                .anyMatch(value -> value.getAsString().equals(bucket));
    }

    @Test
    void seedThreeBoundaryRequiresRowsFromTheActualJoinAndIsNotOrdinaryFiltering() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = query(fixture, TiDBJoin.JoinType.INNER, comparison(fixture, 0, 10));
            execute(fixture, "INSERT INTO t0(k) VALUES(10),(5)");
            execute(fixture, "INSERT INTO t1(k) VALUES(5)");
            JsonObject isolated = packet(fixture, query, 0);
            assertEquals("1", count(isolated, "base_where_boundary_at"));
            assertEquals("0", count(isolated, "where_boundary_at_before"));
            assertEquals("0", count(isolated, "where_boundary_at_after"));
            JsonObject coverage = TiDBMixedDataCoverage.snapshot(isolated);
            assertTrue(present(coverage, "base_data", "base_where_boundary_at"));
            assertTrue(present(coverage, "query_stages", "j0_filtered_matches"));
            assertFalse(present(coverage, "query_stages", "where_boundary_at_before"));
            execute(fixture, "INSERT INTO t1(k) VALUES(10)");
            JsonObject matched = packet(fixture, query, 1);
            assertEquals("1", count(matched, "where_boundary_at_before"));
            assertEquals("0", count(matched, "where_boundary_at_after"));
            JsonObject change = TiDBMixedDataCoverage.difference(coverage, TiDBMixedDataCoverage.snapshot(matched));
            assertTrue(change.getAsJsonObject("query_stages").getAsJsonArray("added").asList().stream()
                    .anyMatch(value -> value.getAsString().equals("where_boundary_at_before")));
            execute(fixture, "INSERT INTO t0(k) VALUES(9),(11)");
            execute(fixture, "INSERT INTO t1(k) VALUES(9),(11)");
            JsonObject adjacent = packet(fixture, query, 2);
            assertEquals("1", count(adjacent, "where_boundary_below_before"));
            assertEquals("0", count(adjacent, "where_boundary_below_after"));
            assertEquals("1", count(adjacent, "where_boundary_above_before"));
            assertEquals("1", count(adjacent, "where_boundary_above_after"));
            JsonObject record = new JsonObject();
            record.addProperty("validation",
                    "Scripted H2 data and real read-only probe SQL; no DeepSeek or TiDB/TiFlash run");
            record.add("isolated_boundary", isolated);
            record.add("isolated_coverage", coverage);
            record.add("matched_boundary", matched);
            record.add("matched_change", change);
            record.add("adjacent_boundaries", adjacent);
            TiDBMixedSupport.save(Path.of("target/mixed-preparation/query-stage-boundary.json"), record);
        }
    }

    @Test
    void baseNullDoesNotProveNullAtWhereOrAggregateInput() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            execute(fixture, "INSERT INTO t1(k) VALUES(NULL),(1)");
            Candidate query = query(fixture, TiDBJoin.JoinType.INNER, comparison(fixture, 0, 0));
            TiDBExpression right = new TiDBColumnReference(fixture.tables.get(1).getColumns().get(0));
            query.ast.setFetchColumns(List.of(new TiDBAggregate(List.of(right), TiDBAggregateFunction.SUM)));
            query = new Candidate(query.ast, query.tables, List.of());
            JsonObject packet = packet(fixture, query, 0);
            assertEquals("1", count(packet, "nulls_t1.k"));
            assertEquals("0", count(packet, "where_input_nulls_t1.k"));
            assertEquals("0", count(packet, "aggregate_input_nulls_t1.k"));
            assertEquals("0", count(packet, "aggregate_arg0_nulls"));
            JsonObject coverage = TiDBMixedDataCoverage.snapshot(packet);
            assertTrue(present(coverage, "base_data", "nulls_present_t1.k"));
            assertFalse(present(coverage, "query_stages", "where_input_nulls_t1.k"));
            assertFalse(coverage.getAsJsonArray("present").asList().stream()
                    .anyMatch(value -> value.getAsString().equals("nulls_present_t1.k")));
        }
    }

    @Test
    void outerNullExtensionAndUnmatchedOutputAreCountedAfterWhereAndBeforeGrouping() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = query(fixture, TiDBJoin.JoinType.LEFT, comparison(fixture, 0, 0));
            TiDBExpression right = new TiDBColumnReference(fixture.tables.get(1).getColumns().get(0));
            query.ast.setFetchColumns(List.of(new TiDBAggregate(List.of(right), TiDBAggregateFunction.SUM)));
            query.ast.setGroupByExpressions(List.of(right));
            query = new Candidate(query.ast, query.tables, List.of());
            JsonObject packet = packet(fixture, query, 0);
            assertEquals("0", count(packet, "nulls_t1.k"));
            for (String id : List.of("where_input_nulls_t1.k", "where_output_nulls_t1.k", "aggregate_input_nulls_t1.k",
                    "aggregate_arg0_nulls", "group_input_nulls_t1.k", "j0_left_unmatched_after")) {
                assertEquals("1", count(packet, id), id);
                assertTrue(present(TiDBMixedDataCoverage.snapshot(packet), "query_stages", id), id);
            }
            query.ast.setWhereClause(comparison(fixture, 1, 0));
            query = new Candidate(query.ast, query.tables, List.of());
            JsonObject removed = packet(fixture, query, 1);
            assertEquals("1", count(removed, "j0_left_unmatched"));
            assertEquals("1", count(removed, "where_input_nulls_t1.k"));
            assertEquals("1", count(removed, "where_predicate_unknown"));
            for (String id : List.of("where_output_nulls_t1.k", "aggregate_input_nulls_t1.k", "aggregate_arg0_nulls",
                    "group_input_nulls_t1.k", "j0_left_unmatched_after")) {
                assertEquals("0", count(removed, id), id);
                assertFalse(present(TiDBMixedDataCoverage.snapshot(removed), "query_stages", id), id);
            }
        }
    }

    @Test
    void rightUnmatchedOutputIsCreditedOnlyOnThePreservedSide() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            execute(fixture, "INSERT INTO t1(k) VALUES(2)");
            JsonObject packet = packet(fixture, query(fixture, TiDBJoin.JoinType.RIGHT, null), 0);
            assertEquals("1", count(packet, "j0_right_unmatched_after"));
            assertEquals("0", count(packet, "j0_left_unmatched_after"));
            JsonObject coverage = TiDBMixedDataCoverage.snapshot(packet);
            assertTrue(present(coverage, "query_stages", "j0_right_unmatched_after"));
            assertFalse(present(coverage, "query_stages", "j0_left_unmatched_after"));
        }
    }

    @Test
    void conjunctionAndReversedPredicatesKeepTheCompleteWhereAndUnsupportedOrIsExplicit() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            execute(fixture, "INSERT INTO t0(k) VALUES(10),(11)");
            execute(fixture, "INSERT INTO t1(k) VALUES(10),(11)");
            TiDBExpression reversed = new TiDBBinaryComparisonOperation(TiDBConstant.createIntConstant(10),
                    new TiDBColumnReference(fixture.tables.get(0).getColumns().get(0)),
                    TiDBComparisonOperator.SMALLER_EQUALS);
            TiDBExpression blocked = comparison(fixture, 1, 20);
            Candidate query = query(fixture, TiDBJoin.JoinType.INNER,
                    new TiDBBinaryLogicalOperation(reversed, blocked, TiDBBinaryLogicalOperator.AND));
            JsonObject packet = packet(fixture, query, 0);
            assertEquals("1", count(packet, "where_boundary_l_at_before"));
            assertEquals("0", count(packet, "where_boundary_l_at_after"));
            query.ast.setWhereClause(new TiDBBinaryLogicalOperation(reversed, blocked, TiDBBinaryLogicalOperator.OR));
            query = new Candidate(query.ast, query.tables, List.of());
            JsonObject unsupported = packet(fixture, query, 1);
            assertEquals("unsupported", fact(unsupported, "where_boundary_unsupported").get("status").getAsString());
            assertFalse(TiDBMixedDataCoverage.snapshot(unsupported).getAsJsonArray("unsupported").isEmpty());
            JsonObject plan = MixedPreparationTest.response(unsupported, MixedPreparationTest.stop());
            JsonArray goals = new JsonArray();
            goals.add(MixedAppendTest.json("{\"capability_id\":\"where_boundary_at_before\",\"field\":\"count\","
                    + "\"comparison\":\"at_least\",\"expected\":\"1\"}"));
            plan.add("structured_goals", goals);
            assertThrows(IllegalArgumentException.class,
                    () -> TiDBDataPreparationProtocol.decision(plan.toString(), unsupported));
        }
    }

    @Test
    void boundaryArithmeticDoesNotOverflowAndSampledOrStaleStageFactsGiveNoCredit() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = query(fixture, TiDBJoin.JoinType.INNER, comparison(fixture, 0, Long.MAX_VALUE));
            TiDBMixedObservations facts = new TiDBMixedObservations(query, "edge", 0, 0);
            String above = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE).toString();
            assertTrue(facts.available().asList().stream().map(JsonElement::getAsJsonObject)
                    .anyMatch(cap -> cap.get("id").getAsString().equals("where_boundary_above_before")
                            && cap.get("description").getAsString().contains(above)));
            query = query(fixture, TiDBJoin.JoinType.LEFT, null);
            JsonObject packet = packet(fixture, query, 0);
            JsonObject fact = fact(packet, "where_output_nulls_t1.k");
            assertTrue(present(TiDBMixedDataCoverage.snapshot(packet), "query_stages", "where_output_nulls_t1.k"));
            for (String status : List.of("sampled", "unknown", "error")) {
                fact.addProperty("status", status);
                JsonObject coverage = TiDBMixedDataCoverage.snapshot(packet);
                assertFalse(present(coverage, "query_stages", "where_output_nulls_t1.k"));
                assertFalse(TiDBMixedDataCoverage.difference(coverage, coverage).get("complete").getAsBoolean());
            }
            fact.addProperty("status", "exact");
            fact.addProperty("data_revision", 99);
            assertFalse(present(TiDBMixedDataCoverage.snapshot(packet), "query_stages", "where_output_nulls_t1.k"));
        }
    }

    @Test
    void reportsBaseOnlyGainSeparatelyAndDoesNotUpgradeHistoricalUnscopedResults() {
        JsonObject scoped = MixedAppendTest.json("{\"strategy\":\"AI\",\"status\":\"COMPLETED\",\"elapsed_ms\":1,"
                + "\"api_audit\":[],\"engine_outcome\":{\"allPathsValid\":true},\"data_coverage_change\":{"
                + "\"added\":[],\"lost\":[],\"complete\":true,"
                + "\"base_data\":{\"added\":[\"nulls_present_t1.k\"],\"lost\":[],\"complete\":true},"
                + "\"query_stages\":{\"added\":[],\"lost\":[],\"complete\":true}}}");
        JsonObject legacy = scoped.deepCopy();
        legacy.getAsJsonObject("data_coverage_change").remove("base_data");
        legacy.getAsJsonObject("data_coverage_change").remove("query_stages");
        legacy.getAsJsonObject("data_coverage_change").getAsJsonArray("added").add("nulls_present_t1.k");
        JsonArray reports = new JsonArray();
        reports.add(scoped);
        reports.add(legacy);
        JsonObject summary = TiDBMixedBenchmarkMetrics.summarize(reports).getAsJsonObject("AI");
        assertEquals(1, summary.getAsJsonObject("base_data").get("new_hits").getAsInt());
        assertEquals(0, summary.getAsJsonObject("query_stages").get("new_hits").getAsInt());
        assertEquals(0, summary.getAsJsonObject("query_stages").get("new_effective_scene_hits").getAsInt());
        assertEquals(1, summary.getAsJsonObject("query_stages").get("missing_scoped_reports").getAsInt());
        assertEquals(0, summary.get("new_observed_scene_hits").getAsInt());
        assertEquals(0, summary.get("new_effective_scene_hits").getAsInt());
        assertEquals(1, summary.get("legacy_unscoped_scene_hits").getAsInt());
    }

    @Test
    void aggregateArgumentNullIsMeasuredRatherThanInferredFromItsReferencedColumn() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = query(fixture, TiDBJoin.JoinType.LEFT, null);
            TiDBExpression right = new TiDBColumnReference(fixture.tables.get(1).getColumns().get(0));
            TiDBExpression nullToZero = new TiDBCase(TiDBConstant.createBooleanConstant(true),
                    List.of(new TiDBUnaryPostfixOperation(right, TiDBUnaryPostfixOperator.IS_NULL)),
                    List.of(TiDBConstant.createIntConstant(0)), right);
            query.ast.setFetchColumns(List.of(new TiDBAggregate(List.of(nullToZero), TiDBAggregateFunction.SUM)));
            query = new Candidate(query.ast, query.tables, List.of());
            JsonObject zero = packet(fixture, query, 0);
            assertEquals("1", count(zero, "aggregate_input_nulls_t1.k"));
            assertEquals("0", count(zero, "aggregate_arg0_nulls"));
            execute(fixture, "INSERT INTO t1(k) VALUES(1)");
            TiDBExpression oneToNull = new TiDBCase(right, List.of(TiDBConstant.createIntConstant(1)),
                    List.of(TiDBConstant.createNullConstant()), TiDBConstant.createIntConstant(0));
            query.ast.setFetchColumns(List.of(new TiDBAggregate(List.of(oneToNull), TiDBAggregateFunction.SUM)));
            query = new Candidate(query.ast, query.tables, List.of());
            JsonObject nullValue = packet(fixture, query, 1);
            assertEquals("0", count(nullValue, "aggregate_input_nulls_t1.k"));
            assertEquals("1", count(nullValue, "aggregate_arg0_nulls"));
        }
    }

    @Test
    void localMatchingPairsDoNotProveArrivalAtWhereInAThreeTableQuery() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            execute(fixture, "CREATE TABLE t2(k INT)");
            execute(fixture, "INSERT INTO t0(k) VALUES(10)");
            execute(fixture, "INSERT INTO t1(k) VALUES(10)");
            sqlancer.tidb.TiDBSchema.TiDBTable third = MixedObservationTest.table("t2");
            Candidate pair = query(fixture, TiDBJoin.JoinType.INNER, comparison(fixture, 0, 10));
            TiDBExpression left = new TiDBColumnReference(fixture.tables.get(0).getColumns().get(0));
            TiDBExpression right = new TiDBColumnReference(third.getColumns().get(0));
            pair.ast.setJoinList(List.of(
                    new TiDBJoin(pair.ast.getJoinList().get(0), new TiDBTableReference(third), TiDBJoin.JoinType.INNER,
                            new TiDBBinaryComparisonOperation(left, right, TiDBComparisonOperator.EQUALS))));
            Candidate query = new Candidate(pair.ast, List.of(fixture.tables.get(0), fixture.tables.get(1), third),
                    List.of());
            TiDBMixedObservations facts = new TiDBMixedObservations(query, "three_tables", 0, 0);
            facts.collect(new TiDBMixedSupport(fixture.state), List.of("j1_pairs_before", "where_boundary_at_before"),
                    new TiDBMixedObservations.Budget(2, 64, 30_000));
            assertEquals("1", MixedObservationTest.count(facts, "j1_pairs_before"));
            assertEquals("0", MixedObservationTest.count(facts, "where_boundary_at_before"));
        }
    }

    @Test
    void aiStageGoalsAreAutomaticallyRecheckedAndADataOnlyAppendGetsNoQueryCoverageCredit() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = query(fixture, TiDBJoin.JoinType.INNER, comparison(fixture, 0, 10));
            JsonObject initial = packet(fixture, query, 0);
            AtomicInteger calls = new AtomicInteger();
            TiDBMixedPreparationHarness harness = new TiDBMixedPreparationHarness(fixture.state, query, initial,
                    List.of(), 100, 64, 2);
            harness.verificationFloor(TiDBMixedDataCoverage
                    .measurementIds(new TiDBMixedObservations(query, initial.get("query_id").getAsString(), 0, 0)));
            JsonObject result = harness.run(messages -> {
                JsonObject state = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                int call = calls.getAndIncrement();
                JsonObject plan = MixedPreparationTest.response(state,
                        call < 2 ? MixedPreparationTest.append(10, false) : MixedPreparationTest.stop());
                if (call < 2) {
                    JsonArray goals = new JsonArray();
                    goals.add(
                            MixedAppendTest.json("{\"capability_id\":\"where_boundary_at_before\",\"field\":\"count\","
                                    + "\"comparison\":\"at_least\",\"expected\":\"1\"}"));
                    plan.add("structured_goals", goals);
                }
                if (call == 1) {
                    plan.add("operations", JsonParser.parseString(
                            "[{\"table\":\"t1\",\"rows\":[" + "{\"k\":{\"type\":\"integer\",\"value\":\"10\"}}]}]"));
                }
                return plan.toString();
            });
            JsonObject first = result.getAsJsonArray("rounds").get(0).getAsJsonObject();
            JsonObject second = result.getAsJsonArray("rounds").get(1).getAsJsonObject();
            assertEquals("not_achieved", first.getAsJsonObject("model_goal_evaluation").get("outcome").getAsString());
            assertEquals("achieved", second.getAsJsonObject("model_goal_evaluation").get("outcome").getAsString());
            assertTrue(first.getAsJsonObject("data_coverage_change").getAsJsonArray("added").isEmpty());
            assertFalse(first.getAsJsonObject("data_coverage_change").getAsJsonObject("base_data")
                    .getAsJsonArray("added").isEmpty());
            JsonObject report = MixedAppendTest
                    .json("{\"strategy\":\"AI\",\"status\":\"COMPLETED\",\"elapsed_ms\":1,\"api_audit\":[]}");
            JsonObject preparation = new JsonObject();
            JsonArray rounds = new JsonArray();
            rounds.add(first);
            preparation.add("rounds", rounds);
            report.add("preparation", preparation);
            JsonArray reports = new JsonArray();
            reports.add(report);
            JsonObject metrics = TiDBMixedBenchmarkMetrics.summarize(reports).getAsJsonObject("AI");
            assertEquals(1, metrics.get("base_data_only_append_rounds").getAsInt());
            assertEquals(1, metrics.get("rows_without_new_scene").getAsInt());
            assertEquals(0, metrics.get("productive_append_rounds").getAsInt());
            TiDBMixedSupport.save(Path.of("target/mixed-preparation/query-stage-model-goals.json"), result);
        }
    }
}
