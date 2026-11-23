package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

class MixedObservationReuseTest {
    private static boolean offers(JsonArray tools, String action, String field, String capability) {
        JsonObject function = tools.asList().stream().map(JsonElement::getAsJsonObject)
                .map(tool -> tool.getAsJsonObject("function"))
                .filter(tool -> tool.get("name").getAsString().equals(action)).findFirst().orElseThrow();
        return function.getAsJsonObject("parameters").getAsJsonObject("properties").getAsJsonObject(field)
                .getAsJsonObject("items").getAsJsonArray("anyOf").asList().stream().map(JsonElement::getAsJsonObject)
                .anyMatch(branch -> branch.getAsJsonObject("properties").getAsJsonObject("capability_id")
                        .getAsJsonArray("enum").contains(new com.google.gson.JsonPrimitive(capability)));
    }

    @Test
    void advertisedObservationsOmitCachedStaticFactsButVerificationStillOffersThem() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            TiDBMixedObservations facts = new TiDBMixedObservations(query, "query_loop", 0, 0);
            facts.collect(new TiDBMixedSupport(fixture.state), List.of("j0_pairs_after"),
                    new TiDBMixedObservations.Budget(1, 64, 10_000));
            packet.add("observations", facts.observations.deepCopy());
            JsonArray tools = TiDBMixedActionSchema.tools(packet);
            assertFalse(offers(tools, "observe", "observation_requests", "j0_pairs_after"));
            assertTrue(offers(tools, "observe", "observation_requests", "rows_t0"));
            assertTrue(offers(tools, "append_rows", "verification_requests", "j0_pairs_after"));
            // Catalog identities and historical/cache requests remain valid; only advertised choices are narrowed.
            TiDBDataPreparationProtocol.decision(MixedPreparationTest.response(packet, observe()).toString(), packet);
        }
    }

    @Test
    void unchangedLimitedSamplesDoNotCountAsInformationAndCannotConsumeAllFutureActionSlots() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            try (java.sql.Statement statement = fixture.database.createStatement()) {
                statement.execute("INSERT INTO t1 VALUES(1),(2),(3)");
            }
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            packet.add("remaining_budget", TiDBMixedPreparationHarness.preparationBudget());
            packet.getAsJsonObject("remaining_budget").addProperty("observation_calls", 6);
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 1, 2)
                    .run(messages -> {
                        JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        int index = calls.getAndIncrement();
                        if (index < 3) {
                            JsonObject action = observe();
                            action.getAsJsonArray("observation_requests").set(0,
                                    new com.google.gson.JsonPrimitive("sample_t1"));
                            return MixedPreparationTest.response(current, action).toString();
                        }
                        assertEquals(3, current.getAsJsonObject("remaining_budget").get("decision_calls").getAsInt());
                        assertFalse(TiDBMixedActionSchema.observationAllowed(current));
                        JsonObject afterWrite = current.deepCopy();
                        afterWrite.addProperty("data_revision", 1);
                        assertTrue(TiDBMixedActionSchema.observationAllowed(afterWrite));
                        return MixedPreparationTest.response(current, MixedPreparationTest.stop()).toString();
                    });
            assertEquals(4, calls.get());
            for (int i = 1; i <= 2; i++) {
                JsonObject feedback = result.getAsJsonArray("rounds").get(i).getAsJsonObject()
                        .getAsJsonObject("observation_feedback");
                assertFalse(feedback.get("information_increment").getAsBoolean());
                assertEquals(0, feedback.get("changed_samples").getAsInt());
                assertEquals(1, feedback.get("unchanged_samples").getAsInt());
            }
        }
    }

    private static JsonObject observe() {
        return MixedAppendTest.json("{\"decision\":\"observe\",\"reason\":\"measure join\",\"goal\":\"match\","
                + "\"expected_effect\":\"facts\",\"observation_requests\":[\"j0_pairs_after\"]}");
    }

    @Test
    void threeObservationsDoNotSpendAppendAdjustStopQuotaAndRepeatedFactsRunOnlyOnce() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            packet.add("remaining_budget", TiDBMixedPreparationHarness.preparationBudget());
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 64, 2)
                    .run(messages -> {
                        JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        int index = calls.getAndIncrement();
                        if (index < 3) {
                            assertEquals(3,
                                    current.getAsJsonObject("remaining_budget").get("decision_calls").getAsInt());
                            return MixedPreparationTest.response(current, observe()).toString();
                        }
                        assertFalse(TiDBMixedActionSchema.tools(current).asList().stream()
                                .anyMatch(tool -> tool.getAsJsonObject().getAsJsonObject("function").get("name")
                                        .getAsString().equals("observe")));
                        if (index == 4) {
                            JsonObject previous = current.getAsJsonArray("history").get(3).getAsJsonObject();
                            assertEquals(1, previous.getAsJsonArray("writes").size());
                            assertTrue(previous.has("cumulative_model_goal_evaluation"));
                            assertEquals(2,
                                    current.getAsJsonObject("remaining_budget").get("decision_calls").getAsInt());
                        }
                        JsonObject action = index < 5 ? MixedPreparationTest.append(12, true)
                                : MixedPreparationTest.stop();
                        if (index == 4) {
                            assertEquals("0", current.getAsJsonArray("observations").get(0).getAsJsonObject()
                                    .getAsJsonObject("value").get("count").getAsString());
                            JsonObject operation = action.getAsJsonArray("operations").get(0).getAsJsonObject();
                            operation.addProperty("table", "t1");
                            JsonObject row = operation.getAsJsonArray("rows").get(0).getAsJsonObject();
                            for (String unused : List.of("n", "s", "b")) {
                                row.remove(unused);
                            }
                        }
                        if (index == 5) {
                            assertEquals("1", current.getAsJsonArray("observations").get(0).getAsJsonObject()
                                    .getAsJsonObject("value").get("count").getAsString());
                        }
                        return MixedPreparationTest.response(current, action).toString();
                    });
            assertEquals(6, calls.get());
            assertEquals("model_stop", result.get("termination").getAsString());
            JsonArray rounds = result.getAsJsonArray("rounds");
            assertEquals(1, rounds.get(0).getAsJsonObject().getAsJsonArray("probe_audit").size());
            for (int i = 1; i <= 2; i++) {
                JsonObject feedback = rounds.get(i).getAsJsonObject().getAsJsonObject("observation_feedback");
                assertFalse(rounds.get(i).getAsJsonObject().has("probe_audit"));
                assertFalse(feedback.get("information_increment").getAsBoolean());
                assertEquals(1, feedback.getAsJsonArray("reused_evidence_ids").size());
            }
            assertEquals(1, rounds.get(4).getAsJsonObject().getAsJsonArray("writes").size());
            JsonObject remaining = result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget");
            assertEquals(0, remaining.get("decision_calls").getAsInt());
            assertEquals(0, remaining.get("observation_calls").getAsInt());
            assertEquals(95, remaining.get("probe_requests").getAsInt()); // Only the active observation.
            assertEquals(382, remaining.get("verification_probe_requests").getAsInt()); // Two remeasurements.
        }
    }

    @Test
    void cachedFactsRemainFreeAtZeroProbeBudgetButChangedVersionsAndSamplesDoNotReuse() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            TiDBMixedObservations facts = new TiDBMixedObservations(query, "query_loop", 0, 0);
            facts.collect(new TiDBMixedSupport(fixture.state), List.of("j0_pairs_after", "sample_t0"),
                    new TiDBMixedObservations.Budget(2, 1, 10_000));
            packet.add("observations", facts.observations.deepCopy());
            packet.add("samples", facts.samples.deepCopy());
            packet.getAsJsonObject("remaining_budget").addProperty("probe_requests", 0);
            assertTrue(TiDBMixedObservationRequest.currentExact(packet,
                    observe().getAsJsonArray("observation_requests").get(0)) != null);
            assertTrue(TiDBMixedObservationRequest.currentExact(packet,
                    new com.google.gson.JsonPrimitive("sample_t0")) != null);
            JsonObject fact = packet.getAsJsonArray("observations").get(1).getAsJsonObject();
            fact.addProperty("status", "sampled");
            assertEquals(null,
                    TiDBMixedObservationRequest.currentExact(packet, new com.google.gson.JsonPrimitive("sample_t0")));
            TiDBDataPreparationProtocol.decision(MixedPreparationTest.response(packet, observe()).toString(), packet);
            packet.addProperty("data_revision", 1);
            assertEquals(null, TiDBMixedObservationRequest.currentExact(packet,
                    observe().getAsJsonArray("observation_requests").get(0)));
        }
    }

    @Test
    void finalApiSlotsAreReservedForActionAndFeedbackRatherThanMoreObservation() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            JsonObject packet = MixedPreparationTest.packet(fixture, MixedPreparationTest.query(fixture, false));
            packet.add("remaining_budget", TiDBMixedPreparationHarness.preparationBudget());
            packet.getAsJsonObject("remaining_budget").addProperty("model_calls", 2);
            assertFalse(TiDBMixedActionSchema.observationAllowed(packet));
            TiDBDataPreparationProtocol.ValidationException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    TiDBDataPreparationProtocol.ValidationException.class, () -> TiDBDataPreparationProtocol
                            .decision(MixedPreparationTest.response(packet, observe()).toString(), packet));
            assertEquals("OBSERVATION_BUDGET_EXHAUSTED", failure.code);
        }
    }
}
