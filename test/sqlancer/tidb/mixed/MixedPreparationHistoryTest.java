package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

class MixedPreparationHistoryTest {
    @Test
    void sixLargeObservationRoundsKeepFullAuditWithoutRepeatingItInModelHistory() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            try (Statement sql = fixture.database.createStatement()) {
                for (int i = 2; i < 32; i++) {
                    sql.execute("INSERT INTO t0(k,s) VALUES(" + i + ",REPEAT('x',900))");
                }
            }
            Candidate query = MixedPreparationTest.query(fixture, false);
            JsonObject packet = MixedPreparationTest.packet(fixture, query);
            packet.getAsJsonObject("remaining_budget").addProperty("model_calls", 6);
            packet.getAsJsonObject("remaining_budget").addProperty("probe_requests", 100);
            AtomicInteger calls = new AtomicInteger();
            JsonObject result = new TiDBMixedPreparationHarness(fixture.state, query, packet, List.of(), 100, 64, 2)
                    .run(messages -> {
                        JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                        assertTrue(current.toString().length() < 128000);
                        calls.incrementAndGet();
                        JsonObject script = MixedAppendTest
                                .json("{\"decision\":\"observe\",\"reason\":\"measure sample\","
                                        + "\"goal\":\"retain facts\",\"expected_effect\":\"sample only\","
                                        + "\"observation_requests\":[\"sample_t0\"]}");
                        return MixedPreparationTest.response(current, script).toString();
                    });
            assertEquals(6, calls.get());
            assertEquals(6, result.getAsJsonArray("rounds").size());
            assertTrue(result.getAsJsonArray("rounds").toString().length() > 128000);
            JsonObject finalState = result.getAsJsonObject("final_state");
            assertTrue(finalState.toString().length() < 128000);
            for (com.google.gson.JsonElement entry : finalState.getAsJsonArray("history")) {
                JsonObject summary = entry.getAsJsonObject();
                assertFalse(summary.has("actual_samples"));
                assertFalse(summary.has("actual_observations"));
                assertFalse(summary.has("data_coverage_before"));
                assertFalse(summary.has("probe_audit"));
            }
        }
    }

    @Test
    void historyPreservesErrorPathsActualGoalValuesAndEvidenceIds() {
        JsonObject round = MixedAppendTest.json("{\"validation_feedback\":{\"path\":\"$.goal\",\"example\":\"text\"},"
                + "\"writes\":[],\"model_goal_evaluation\":{\"outcome\":\"not_achieved\",\"checks\":["
                + "{\"actual\":\"0\",\"evidence\":[{\"id\":\"fact_1\",\"value\":{\"count\":\"0\"}}]}]}}");
        JsonObject summary = TiDBMixedPreparationHarness.historySummary(round);
        assertEquals(round.get("validation_feedback"), summary.get("validation_feedback"));
        JsonObject check = summary.getAsJsonObject("model_goal_evaluation").getAsJsonArray("checks").get(0)
                .getAsJsonObject();
        assertEquals("0", check.get("actual").getAsString());
        assertEquals("fact_1", check.getAsJsonArray("evidence_ids").get(0).getAsString());
        assertFalse(check.has("evidence"));
        assertTrue(round.getAsJsonObject("model_goal_evaluation").getAsJsonArray("checks").get(0).getAsJsonObject()
                .has("evidence"));
    }
}
