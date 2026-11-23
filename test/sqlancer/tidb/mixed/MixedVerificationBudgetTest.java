package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class MixedVerificationBudgetTest {
    @Test
    void observingTheLastProbeCannotSpendReservedPostWriteVerification() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            TiDBMixedGeneration.Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            packet.getAsJsonObject("remaining_budget").addProperty("probe_requests", 1);
            packet.getAsJsonObject("remaining_budget").addProperty("verification_probe_requests", 90);
            TiDBMixedPreparationHarness harness = new TiDBMixedPreparationHarness(fixture.state, query, packet,
                    List.of(), 100, 64, 2);
            harness.verificationFloor(List.of("j0_pairs_after", "rows_t0"));
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = harness.run(messages -> {
                JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                int call = calls.getAndIncrement();
                JsonObject action = call == 0
                        ? MixedAppendTest.json("{\"decision\":\"observe\",\"reason\":\"count\",\"goal\":\"measure\","
                                + "\"expected_effect\":\"facts\",\"observation_requests\":[\"j0_pairs_after\"]}")
                        : call == 1 ? MixedPreparationTest.append(12, true) : MixedPreparationTest.stop();
                return MixedPreparationTest.response(current, action).toString();
            });
            assertEquals("model_stop", result.get("termination").getAsString());
            JsonObject append = result.getAsJsonArray("rounds").get(1).getAsJsonObject();
            assertEquals("success",
                    append.getAsJsonArray("writes").get(0).getAsJsonObject().get("status").getAsString());
            JsonObject remaining = result.getAsJsonObject("final_state").getAsJsonObject("remaining_budget");
            assertEquals(0, remaining.get("probe_requests").getAsInt());
            assertTrue(remaining.get("verification_probe_requests").getAsInt() < 90);
            assertEquals("verification_probe_requests",
                    append.getAsJsonObject("observation_feedback").get("budget_pool").getAsString());
        }
    }

    @Test
    void impossibleMandatoryFloorStopsBeforeAnyModelCallOrWrite() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            TiDBMixedGeneration.Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            packet.getAsJsonObject("remaining_budget").addProperty("verification_probe_requests", 1);
            TiDBMixedPreparationHarness harness = new TiDBMixedPreparationHarness(fixture.state, query, packet,
                    List.of(), 100, 64, 2);
            harness.verificationFloor(List.of("j0_pairs_after", "rows_t0"));
            JsonObject result = harness.run(messages -> {
                throw new AssertionError("an impossible program-owned floor is not a model format error");
            });
            assertEquals("verification_budget_exhausted", result.get("termination").getAsString());
            assertTrue(result.getAsJsonArray("rounds").isEmpty());
            assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t0"));
            assertTrue(result.getAsJsonObject("final_state").get("frozen").getAsBoolean());
        }
    }
}
