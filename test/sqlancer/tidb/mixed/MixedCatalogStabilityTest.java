package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.Randomly;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.TiDBSchema.TiDBTable;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation.TiDBComparisonOperator;
import sqlancer.tidb.ast.TiDBColumnReference;
import sqlancer.tidb.ast.TiDBConstant;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.ast.TiDBTableReference;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;
import sqlancer.tidb.visitor.TiDBVisitor;

/** Catalog identities must survive rebuilding facts and preparation rounds for an unchanged AST. */
class MixedCatalogStabilityTest {
    @Test
    void unchangedOuterJoinCandidatesSurviveOneHundredCatalogAndHarnessRebuilds() throws Exception {
        Map<String, Integer> mismatches = new LinkedHashMap<>();
        Map<String, Integer> rejections = new LinkedHashMap<>();
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            for (TiDBJoin.JoinType type : List.of(TiDBJoin.JoinType.LEFT, TiDBJoin.JoinType.RIGHT)) {
                new Randomly(42);
                Candidate query = MixedObservationTest.candidate(fixture.tables, type, TiDBComparisonOperator.EQUALS,
                        false);
                JsonObject packet = MixedPreparationTest.packet(fixture, query);
                int different = 0;
                int rejected = 0;
                for (int i = 0; i < 100; i++) {
                    TiDBMixedObservations facts = new TiDBMixedObservations(query, "query_loop", 0, 0);
                    if (!packet.get("available_observations").equals(facts.available())) {
                        different++;
                    }
                    try {
                        new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 64, 2);
                    } catch (IllegalArgumentException error) {
                        assertEquals("candidate or observation catalog differs from state packet", error.getMessage());
                        rejected++;
                    }
                }
                mismatches.put(type.name(), different);
                rejections.put(type.name(), rejected);
            }
        }
        assertEquals(Map.of("LEFT", 0, "RIGHT", 0), mismatches, "harness rejections: " + rejections);
        assertEquals(Map.of("LEFT", 0, "RIGHT", 0), rejections);
        TiDBMixedSupport.save(Path.of("target/mixed-catalog/rebuilds.json"), Map.of("seed", 42, "rebuilds_per_type",
                100, "catalog_mismatches", mismatches, "harness_rejections", rejections));
    }

    private static Candidate nestedCandidate() {
        List<TiDBTable> tables = List.of(MixedObservationTest.table("t0"), MixedObservationTest.table("t1"),
                MixedObservationTest.table("t2"));
        Candidate query = MixedObservationTest.candidate(tables, TiDBJoin.JoinType.LEFT, TiDBComparisonOperator.EQUALS,
                false);
        query.ast.setJoinList(List.of(new TiDBJoin(query.ast.getJoinList().get(0),
                new TiDBTableReference(tables.get(2)), TiDBJoin.JoinType.RIGHT,
                new TiDBBinaryComparisonOperation(new TiDBColumnReference(tables.get(1).getColumns().get(0)),
                        new TiDBColumnReference(tables.get(2).getColumns().get(0)), TiDBComparisonOperator.EQUALS))));
        query.ast.setWhereClause(
                new TiDBBinaryComparisonOperation(new TiDBColumnReference(tables.get(2).getColumns().get(0)),
                        TiDBConstant.createIntConstant(10), TiDBComparisonOperator.GREATER));
        query.ast.setGroupByExpressions(List.of(new TiDBColumnReference(tables.get(0).getColumns().get(0))));
        return new Candidate(query.ast, tables, List.of());
    }

    private static JsonObject request(String id, String parameters) {
        JsonObject request = new JsonObject();
        request.addProperty("capability_id", id);
        request.add("parameters", JsonParser.parseString(parameters));
        return request;
    }

    private static Map<String, String> compiledProbes(Candidate query) {
        String before = "FROM " + TiDBVisitor.asStringDeterministic(query.ast.getJoinList().get(0));
        TiDBMixedParameterizedObservations compiler = new TiDBMixedParameterizedObservations(query, before);
        Map<String, String> probes = new LinkedHashMap<>();
        for (JsonObject request : List.of(request("predicate_j0_on", "{\"stage\":\"input\"}"),
                request("predicate_j0_on_query", "{\"stage\":\"input\"}"),
                request("key_matches_j0_t1.k",
                        "{\"stage\":\"local_pairs\"," + "\"key\":{\"type\":\"integer\",\"value\":\"1\"}}"),
                request("group_input_rows", "{\"key0\":{\"type\":\"integer\",\"value\":\"1\"}}"))) {
            TiDBMixedObservations.Probe probe = compiler.compile(request);
            probes.put(probe.id, probe.sql + "\n" + probe.bindings);
        }
        return probes;
    }

    @Test
    void nestedOuterJoinCatalogsAndBoundProbeSqlRemainIdenticalAcrossRandomDraws() {
        Candidate query = nestedCandidate();
        JsonArray catalog = new TiDBMixedObservations(query, "nested", 0, 0).available();
        Map<String, String> probes = compiledProbes(query);
        for (int i = 0; i < 100; i++) {
            Randomly.getBoolean();
            assertEquals(catalog, new TiDBMixedObservations(query, "nested", 0, i).available());
            assertEquals(probes, compiledProbes(query));
            assertEquals(query.sql, new Candidate(query.ast, query.tables, List.of()).sql);
        }
        assertTrue(probes.get("predicate_j0_on").contains("LEFT JOIN"));
        assertTrue(probes.get("predicate_j0_on_query").contains("RIGHT JOIN"));
    }

    private static List<Boolean> draws() {
        List<Boolean> draws = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            draws.add(Randomly.getBoolean());
        }
        return draws;
    }

    @Test
    void deterministicRenderingDoesNotConsumeRandomStateAndDefaultVisitorStillVaries() {
        Candidate query = nestedCandidate();
        new Randomly(42);
        List<Boolean> expected = draws();
        new Randomly(42);
        for (int i = 0; i < 100; i++) {
            assertEquals(query.sql, TiDBVisitor.asStringDeterministic(query.ast));
            new Candidate(query.ast, query.tables, List.of());
            new TiDBMixedObservations(query, "nested", 0, 0);
            compiledProbes(query);
        }
        assertEquals(expected, draws());
        assertFalse(query.sql.contains(" OUTER "));
        Set<String> variations = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            variations.add(TiDBVisitor.asString(query.ast));
        }
        assertTrue(variations.size() > 1);
        assertTrue(variations.stream().anyMatch(sql -> sql.contains(" OUTER ")));
    }

    @Test
    void changedQueryDescriptionsAndParameterSignaturesStillFailTheFullCatalogGuard() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedObservationTest.candidate(fixture.tables, TiDBJoin.JoinType.LEFT,
                    TiDBComparisonOperator.EQUALS, false);
            for (String change : List.of("query", "description", "parameters")) {
                JsonObject packet = MixedPreparationTest.packet(fixture, query);
                if (change.equals("query")) {
                    packet.getAsJsonObject("query").addProperty("sql", query.sql + " /* changed */");
                } else {
                    JsonObject capability = packet.getAsJsonArray("available_observations").asList().stream()
                            .map(JsonElement::getAsJsonObject).filter(cap -> cap.has("parameters")).findFirst()
                            .orElseThrow();
                    if (change.equals("description")) {
                        capability.addProperty("description", "changed relation scope");
                    } else {
                        capability.getAsJsonObject("parameters").getAsJsonObject("stage").getAsJsonArray("values")
                                .add("changed_scope");
                    }
                }
                assertEquals("candidate or observation catalog differs from state packet", assertThrows(
                        IllegalArgumentException.class,
                        () -> new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 64, 2))
                                .getMessage());
            }
        }
    }

    static JsonObject outerJoinLoop(TiDBGlobalState state, Candidate query, JsonObject schema) throws Exception {
        JsonObject initial = new TiDBMixedObservations(query, "outer_loop", 0, 0).statePacket("outer", "outer_case",
                schema, new JsonArray(), TiDBMixedPreparationHarness.preparationBudget());
        JsonObject key = request("key_matches_j0_t0.k",
                "{\"stage\":\"query_input\",\"key\":{\"type\":\"integer\",\"value\":\"1\"}}");
        AtomicInteger calls = new AtomicInteger();
        JsonObject result = new TiDBMixedPreparationHarness(state, query, initial, List.of(), 100, 64, 2)
                .run(messages -> {
                    JsonObject packet = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                    assertEquals(initial.get("available_observations"), packet.get("available_observations"));
                    assertEquals(initial.get("query"), packet.get("query"));
                    int call = calls.getAndIncrement();
                    if (call == 0) {
                        JsonObject plan = MixedPreparationTest.response(packet,
                                MixedAppendTest
                                        .json("{\"decision\":\"observe\",\"reason\":\"measure original outer JOIN\","
                                                + "\"goal\":\"find key matches\",\"expected_effect\":\"read only\"}"));
                        plan.getAsJsonArray("observation_requests").add(key);
                        return plan.toString();
                    }
                    JsonObject observation = packet.getAsJsonArray("observations").get(0).getAsJsonObject();
                    assertEquals("exact", observation.get("status").getAsString());
                    assertEquals(call == 1 ? "0" : "1",
                            observation.getAsJsonObject("value").get("count").getAsString());
                    if (call == 1) {
                        JsonObject plan = MixedPreparationTest.response(packet, MixedAppendTest
                                .json("{\"decision\":\"append_rows\",\"reason\":\"supply missing right key\","
                                        + "\"goal\":\"one true ON match in query input\",\"expected_effect\":\"one match\","
                                        + "\"operations\":[{\"table\":\"t1\",\"rows\":[{\"k\":{\"type\":\"integer\",\"value\":\"1\"}}]}]}"));
                        plan.getAsJsonArray("verification_requests").add(key);
                        JsonObject goal = key.deepCopy();
                        goal.addProperty("field", "count");
                        goal.addProperty("comparison", "at_least");
                        goal.addProperty("expected", "1");
                        plan.getAsJsonArray("structured_goals").add(goal);
                        return plan.toString();
                    }
                    return MixedPreparationTest.response(packet, MixedPreparationTest.stop()).toString();
                });
        assertEquals(3, calls.get());
        assertEquals("model_stop", result.get("termination").getAsString());
        assertEquals("achieved", result.getAsJsonObject("model_goal_evaluation").get("outcome").getAsString());
        assertEquals(1, result.getAsJsonObject("final_state").get("data_revision").getAsInt());
        assertTrue(result.getAsJsonArray("rounds").asList().stream()
                .allMatch(round -> "accepted".equals(round.getAsJsonObject().get("validation_status").getAsString())));
        return result;
    }

    @Test
    void leftAndRightJoinObserveAppendStopLoopsKeepCatalogAndValidateActualMatches() throws Exception {
        for (TiDBJoin.JoinType type : List.of(TiDBJoin.JoinType.LEFT, TiDBJoin.JoinType.RIGHT)) {
            try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
                Candidate query = MixedObservationTest.candidate(fixture.tables, type, TiDBComparisonOperator.EQUALS,
                        false);
                JsonObject result = outerJoinLoop(fixture.state, query, fixture.packet.getAsJsonObject("schema"));
                assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t1 WHERE k=1"));
                TiDBMixedSupport.save(Path
                        .of("target/mixed-catalog/" + type.name().toLowerCase(java.util.Locale.ROOT) + "-loop.json"),
                        result);
            }
        }
    }
}
