package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.tidb.ast.TiDBColumnReference;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;
import sqlancer.tidb.mixed.TiDBMixedPreparationHarness.Requirement;

class MixedPreparationGoalsTest {
    private static JsonObject goal(String id, String field, String comparison, String expected) {
        JsonObject goal = new JsonObject();
        goal.addProperty("capability_id", id);
        goal.addProperty("field", field);
        goal.addProperty("comparison", comparison);
        goal.addProperty("expected", expected);
        return goal;
    }

    private static JsonArray goals(JsonObject... goals) {
        JsonArray result = new JsonArray();
        for (JsonObject goal : goals) {
            result.add(goal);
        }
        return result;
    }

    private static String outcome(JsonObject state, JsonArray goals, boolean uncertain) {
        return TiDBMixedPreparationGoals.evaluate(state, goals, uncertain).get("outcome").getAsString();
    }

    private static JsonObject append() {
        JsonObject script = MixedPreparationTest.append(12, true);
        JsonArray operations = JsonParser.parseString(MixedAppendTest.operations(MixedAppendTest.row(12)))
                .getAsJsonArray();
        JsonObject right = new JsonObject();
        right.addProperty("table", "t1");
        JsonObject row = MixedAppendTest.json("{\"k\":{\"type\":\"integer\",\"value\":\"12\"}}");
        right.add("rows", goals(row, row.deepCopy()));
        operations.add(right);
        script.add("operations", operations);
        return script;
    }

    private static void observe(MixedAppendTest.Fixture fixture, Candidate query, JsonObject state, int limit,
            String... ids) throws Exception {
        TiDBMixedObservations facts = new TiDBMixedObservations(query, state.get("query_id").getAsString(), 0,
                state.get("data_revision").getAsLong());
        facts.collect(new TiDBMixedSupport(fixture.state), List.of(ids),
                new TiDBMixedObservations.Budget(ids.length, limit, 30_000));
        state.add("observations", facts.observations.deepCopy());
    }

    @Test
    void productionWithoutFixedRequirementsMeasuresAllModelGoalsAndStopCannotEraseFailures() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            JsonArray goals = goals(goal("j0_pairs_after", "count", "at_least", "2"),
                    goal("j0_duplicate_after", "count", "equals", "1"), goal("nulls_t1.k", "count", "at_least", "1"),
                    goal("j0_left_unmatched_after", "count", "at_least", "1"));
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 64, 2)
                    .run(messages -> {
                        JsonObject state = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        JsonObject plan = MixedPreparationTest.response(state,
                                calls.getAndIncrement() == 0 ? append() : MixedPreparationTest.stop());
                        if (calls.get() == 1) {
                            plan.add("structured_goals", goals);
                            plan.add("verification_requests", new JsonArray());
                            plan.addProperty("verification_limit", "Automatic structured-goal measurements requested");
                        }
                        return plan.toString();
                    });
            JsonObject first = result.getAsJsonArray("rounds").get(0).getAsJsonObject();
            assertEquals(3, first.getAsJsonArray("writes").size());
            assertEquals("unverified", first.getAsJsonObject("evaluation").get("outcome").getAsString());
            JsonObject measured = first.getAsJsonObject("model_goal_evaluation");
            assertEquals("not_achieved", measured.get("outcome").getAsString());
            assertEquals(List.of("achieved", "achieved", "not_achieved", "not_achieved"),
                    measured.getAsJsonArray("checks").asList().stream()
                            .map(e -> e.getAsJsonObject().get("outcome").getAsString())
                            .collect(java.util.stream.Collectors.toList()));
            JsonObject state = result.getAsJsonObject("final_state");
            assertEquals(36, state.getAsJsonObject("remaining_budget").get("probe_requests").getAsInt());
            assertEquals("achieved",
                    TiDBMixedPreparationHarness
                            .evaluate(state,
                                    List.of(Requirement.count("j0_pairs_after", 2, true),
                                            Requirement.count("j0_duplicate_after", 1, true)))
                            .get("outcome").getAsString());
            assertEquals("not_achieved", result.getAsJsonObject("model_goal_evaluation").get("outcome").getAsString());
            assertEquals(goals, result.get("model_goals"));
            TiDBMixedSupport.save(java.nio.file.Path.of("target/mixed-preparation/structured_goals.json"), result);
        }
    }

    @Test
    void goalsCannotExceedProbeBudgetAndCanBeRevisedBeforeWriting() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            packet.getAsJsonObject("remaining_budget").addProperty("probe_requests", 1);
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 64, 2)
                    .run(messages -> {
                        JsonObject state = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        JsonObject plan = MixedPreparationTest.response(state, append());
                        plan.add("verification_requests", new JsonArray());
                        plan.addProperty("verification_limit", "Measure structured goals");
                        JsonArray goals = goals(goal("j0_pairs_after", "count", "at_least", "2"));
                        if (calls.getAndIncrement() == 0) {
                            goals.add(goal("j0_duplicate_after", "count", "at_least", "1"));
                        } else if (calls.get() > 2) {
                            return MixedPreparationTest.response(state, MixedPreparationTest.stop()).toString();
                        }
                        plan.add("structured_goals", goals);
                        return plan.toString();
                    });
            JsonObject rejected = result.getAsJsonArray("rounds").get(0).getAsJsonObject();
            assertTrue(rejected.getAsJsonArray("writes").isEmpty());
            assertEquals(8, rejected.getAsJsonObject("validation_feedback").getAsJsonObject("remaining_budget")
                    .get("insert_rows").getAsInt());
            assertEquals(1, result.getAsJsonArray("model_goals").size());
            assertEquals("achieved", result.getAsJsonObject("model_goal_evaluation").get("outcome").getAsString());
        }
    }

    @Test
    void onlyCurrentExactFactsCanProveGoalsIncludingEmptyResults() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, true);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            JsonArray goals = goals(goal("query_empty", "empty", "equals", "true"));
            assertEquals("unverified", outcome(packet, goals, false));
            observe(fixture, query, packet, 1, "query_empty");
            assertEquals("achieved", outcome(packet, goals, false));
            assertEquals("unverified", outcome(packet, goals, true));
            JsonObject fact = packet.getAsJsonArray("observations").get(0).getAsJsonObject();
            for (String key : List.of("query_id", "schema_revision", "data_revision")) {
                JsonElement saved = fact.get(key);
                if (key.equals("query_id")) {
                    fact.addProperty(key, "other");
                } else {
                    fact.addProperty(key, 99);
                }
                assertEquals("unverified", outcome(packet, goals, false));
                fact.add(key, saved);
            }
            for (String status : List.of("sampled", "unknown", "unsupported", "error")) {
                fact.addProperty("status", status);
                assertEquals("unverified", outcome(packet, goals, false));
            }
            fact.addProperty("status", "exact");
            JsonObject conflict = fact.deepCopy();
            conflict.addProperty("id", "conflicting_fact");
            conflict.getAsJsonObject("value").addProperty("empty", false);
            packet.getAsJsonArray("observations").add(conflict);
            assertEquals("unverified", outcome(packet, goals, false));
            assertEquals("unverified", outcome(packet, new JsonArray(), false));
        }
    }

    @Test
    void validatesCapabilitiesFieldsOperatorsAndExactIntegerStrings() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            JsonArray invalid = goals(goal("invented", "count", "at_least", "1"),
                    goal("sample_t0", "count", "at_least", "1"), goal("j0_pairs_after", "rows", "at_least", "1"),
                    goal("query_empty", "empty", "at_least", "true"), goal("query_empty", "empty", "equals", "1"),
                    goal("j0_pairs_after", "count", "equals", "-1"), goal("j0_pairs_after", "count", "equals", "1.0"),
                    goal("j0_pairs_after", "count", "greater_than", "1"));
            JsonObject numeric = goal("j0_pairs_after", "count", "at_least", "1");
            numeric.addProperty("expected", 1);
            invalid.add(numeric);
            for (JsonElement goal : invalid) {
                JsonObject plan = MixedPreparationTest.response(packet, MixedPreparationTest.stop());
                plan.add("structured_goals", goals(goal.getAsJsonObject()));
                assertThrows(TiDBDataPreparationProtocol.ValidationException.class,
                        () -> TiDBDataPreparationProtocol.decision(plan.toString(), packet));
            }
            JsonObject plan = MixedPreparationTest.response(packet, MixedPreparationTest.stop());
            plan.remove("structured_goals");
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 64, 2)
                    .run(messages -> {
                        JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        for (String key : List.of("request_id", "schema_revision", "data_revision")) {
                            plan.add(key, current.get(key));
                        }
                        return plan.toString();
                    });
            assertTrue(result.getAsJsonArray("model_goals").isEmpty());
            assertEquals("rejected",
                    result.getAsJsonArray("rounds").get(0).getAsJsonObject().get("validation_status").getAsString());
            observe(fixture, query, packet, 64, "j0_pairs_after");
            JsonObject fact = packet.getAsJsonArray("observations").get(0).getAsJsonObject();
            fact.getAsJsonObject("value").addProperty("count", "18446744073709551616");
            assertEquals("achieved",
                    outcome(packet, goals(goal("j0_pairs_after", "count", "at_least", "18446744073709551615")), false));
        }
    }

    @Test
    void outerUnmatchedAndGroupSummaryHaveTheirOwnExactMetrics() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedObservationTest.candidate(fixture.tables, TiDBJoin.JoinType.LEFT,
                    sqlancer.tidb.ast.TiDBBinaryComparisonOperation.TiDBComparisonOperator.EQUALS, false);
            query.ast.setGroupByExpressions(List.of(new TiDBColumnReference(query.tables.get(0).getColumns().get(0))));
            query = new Candidate(query.ast, query.tables, List.of());
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            observe(fixture, query, packet, 64, "j0_pairs_after", "j0_left_unmatched_after", "groups_before_having");
            JsonArray goals = goals(goal("j0_pairs_after", "count", "equals", "0"),
                    goal("j0_left_unmatched_after", "count", "equals", "1"),
                    goal("groups_before_having", "group_count", "equals", "1"),
                    goal("groups_before_having", "input_rows", "equals", "1"),
                    goal("groups_before_having", "min_size", "at_most", "1"),
                    goal("groups_before_having", "max_size", "at_least", "1"));
            JsonObject evaluation = TiDBMixedPreparationGoals.evaluate(packet, goals, false);
            assertEquals("achieved", evaluation.get("outcome").getAsString(), evaluation.toString());
            assertFalse(evaluation.getAsJsonArray("checks").isEmpty());
        }
    }

    @Test
    void uncertainCommitMakesAcceptedGoalsUnverifiedAndStopsCalls() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            fixture.loseCommitReply = true;
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 64, 2)
                    .run(messages -> {
                        calls.incrementAndGet();
                        JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        JsonObject plan = MixedPreparationTest.response(current, append());
                        plan.add("structured_goals", goals(goal("j0_pairs_after", "count", "at_least", "2")));
                        return plan.toString();
                    });
            assertEquals(1, calls.get());
            assertEquals("requires_rebuild", result.get("termination").getAsString());
            assertEquals("unverified", result.getAsJsonObject("model_goal_evaluation").get("outcome").getAsString());
            assertEquals("write_outcome_uncertain", result.getAsJsonObject("model_goal_evaluation")
                    .getAsJsonArray("checks").get(0).getAsJsonObject().get("reason").getAsString());
        }
    }

    @Test
    void meaningfulEmptyGoalCanStopWithoutAnyAppend() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, true);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            observe(fixture, query, packet, 1, "query_empty");
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 64, 2)
                    .run(messages -> {
                        JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        JsonObject plan = MixedPreparationTest.response(current, MixedPreparationTest.stop());
                        plan.add("structured_goals", goals(goal("query_empty", "empty", "equals", "true")));
                        return plan.toString();
                    });
            assertTrue(result.getAsJsonArray("rounds").get(0).getAsJsonObject().getAsJsonArray("writes").isEmpty());
            assertEquals("achieved", result.getAsJsonObject("model_goal_evaluation").get("outcome").getAsString());
        }
    }
}
