package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

class MixedDecisionBudgetTest {
    @Test
    void twoFormatRepairsPreserveObserveAppendAndStopDecisions() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            JsonObject budget = packet.getAsJsonObject("remaining_budget");
            budget.addProperty("model_calls", 6);
            budget.addProperty("decision_calls", 3);
            budget.addProperty("format_repairs", 3);
            AtomicInteger cursor = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 64, 2)
                    .run(messages -> {
                        JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        int index = cursor.getAndIncrement();
                        if (index < 2) {
                            JsonObject rejected = MixedPreparationTest.response(current, MixedPreparationTest.stop());
                            rejected.add("goal", new JsonObject());
                            return rejected.toString();
                        }
                        assertEquals(3 - (index - 2),
                                current.getAsJsonObject("remaining_budget").get("decision_calls").getAsInt());
                        if (index == 2) {
                            return MixedPreparationTest.response(current, MixedAppendTest
                                    .json("{\"decision\":\"observe\",\"reason\":\"count\",\"goal\":\"measure\","
                                            + "\"expected_effect\":\"measure only\",\"observation_requests\":[\"j0_pairs_after\"]}"))
                                    .toString();
                        }
                        return MixedPreparationTest.response(current,
                                index == 3 ? MixedPreparationTest.append(12, true) : MixedPreparationTest.stop())
                                .toString();
                    });
            assertEquals(5, cursor.get());
            assertEquals("model_stop", result.get("termination").getAsString());
            JsonObject remaining = result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget");
            assertEquals(0, remaining.get("decision_calls").getAsInt());
            assertEquals(1, remaining.get("format_repairs").getAsInt());
            assertEquals(1, remaining.get("model_calls").getAsInt());
            JsonObject write = result.getAsJsonArray("rounds").get(3).getAsJsonObject();
            assertEquals(1, write.getAsJsonArray("writes").size());
            assertTrue(write.getAsJsonArray("actual_observations").size() > 0);
        }
    }

    @Test
    void lastValidDecisionStillWritesAndVerifiesWithoutAnotherModelCall() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            packet.getAsJsonObject("remaining_budget").addProperty("decision_calls", 1);
            packet.getAsJsonObject("remaining_budget").addProperty("format_repairs", 1);
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 64, 2)
                    .run(messages -> {
                        calls.incrementAndGet();
                        JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        return MixedPreparationTest.response(current, MixedPreparationTest.append(12, true)).toString();
                    });
            assertEquals(1, calls.get());
            assertEquals(1, result.getAsJsonArray("rounds").get(0).getAsJsonObject().getAsJsonArray("writes").size());
            assertTrue(result.getAsJsonArray("rounds").get(0).getAsJsonObject().getAsJsonArray("actual_observations")
                    .size() > 0);
        }
    }

    @Test
    void actualUsageIsChargedAndMissingUsageCannotFundAnotherCall() throws Exception {
        for (boolean known : List.of(true, false)) {
            try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
                Candidate query = MixedPreparationTest.query(fixture, false);
                JsonObject packet = MixedPreparationTest.packet(fixture, query);
                JsonObject budget = packet.getAsJsonObject("remaining_budget");
                budget.addProperty("model_calls", 6);
                budget.addProperty("decision_calls", 3);
                budget.addProperty("format_repairs", 3);
                budget.addProperty("api_tokens", 500000);
                budget.addProperty("cost_microusd", 250000);
                AtomicInteger calls = new AtomicInteger();
                TiDBDeepSeekPreparationClient client = new TiDBDeepSeekPreparationClient(body -> {
                    calls.incrementAndGet();
                    JsonObject request = JsonParser.parseString(body).getAsJsonObject();
                    JsonObject current = JsonParser.parseString(
                            request.getAsJsonArray("messages").get(2).getAsJsonObject().get("content").getAsString())
                            .getAsJsonObject();
                    JsonObject action = known ? MixedPreparationTest.stop() : MixedAppendTest
                            .json("{\"decision\":\"observe\",\"reason\":\"measure\",\"goal\":\"facts\","
                                    + "\"expected_effect\":\"measure\",\"observation_requests\":[\"j0_pairs_after\"]}");
                    JsonObject envelope = MixedAppendTest.json("{\"id\":\"fixture\",\"model\":\"fixture\","
                            + "\"choices\":[{\"finish_reason\":\"stop\",\"message\":{}}]}");
                    envelope.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message")
                            .addProperty("content", MixedPreparationTest.response(current, action).toString());
                    if (known) {
                        envelope.add("usage",
                                MixedAppendTest.json("{\"prompt_tokens\":1000,\"completion_tokens\":200}"));
                    }
                    return new TiDBDeepSeekAdvisor.Reply(200, envelope.toString());
                }, "fixture", 6, 4000, BigDecimal.ZERO, new BigDecimal("0.15"), new BigDecimal("0.60"));
                JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 64, 2)
                        .run(client);
                assertEquals(1, calls.get());
                JsonObject remaining = result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget");
                assertEquals(known ? 498800 : 0, remaining.get("api_tokens").getAsInt());
                assertEquals(known ? 249730 : 0, remaining.get("cost_microusd").getAsInt());
                assertEquals(known ? "model_stop" : "api_budget_exhausted", result.get("termination").getAsString());
            }
        }
    }

    @Test
    void insufficientTokenReservationNeverSendsHttpRequest() throws Exception {
        JsonObject state = MixedActionSchemaTest.corpus().get(0).getAsJsonObject().getAsJsonObject("state");
        state.getAsJsonObject("remaining_budget").addProperty("api_tokens", 1);
        state.getAsJsonObject("remaining_budget").addProperty("cost_microusd", 250000);
        AtomicInteger sent = new AtomicInteger();
        TiDBDeepSeekPreparationClient client = new TiDBDeepSeekPreparationClient(body -> {
            sent.incrementAndGet();
            throw new AssertionError("must reserve before HTTP");
        }, "fixture", 6, 4000, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE, true, false);
        assertThrows(IllegalArgumentException.class,
                () -> client.respond(TiDBDataPreparationProtocol.messages(state.toString())));
        assertEquals(0, sent.get());
        assertTrue(client.audit.isEmpty());
    }
}
