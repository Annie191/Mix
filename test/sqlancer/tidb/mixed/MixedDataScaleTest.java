package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class MixedDataScaleTest {
    private static JsonObject fixture() throws Exception {
        return JsonParser
                .parseString(Files.readString(Path.of("docs/data-preparation-v2/seed63-scale-observations.json")))
                .getAsJsonObject();
    }

    private static JsonObject observation(JsonObject packet, String id) {
        return packet.getAsJsonArray("observations").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(fact -> id.equals(fact.get("capability_id").getAsString())).findFirst().orElseThrow();
    }

    private static JsonObject difference(JsonObject fixture) {
        return TiDBMixedDataCoverage.difference(TiDBMixedDataCoverage.snapshot(fixture.getAsJsonObject("before")),
                TiDBMixedDataCoverage.snapshot(fixture.getAsJsonObject("after")));
    }

    private static JsonObject scale(JsonObject difference) {
        return difference.getAsJsonObject("scale").getAsJsonObject("query_stages");
    }

    private static JsonObject change(JsonObject scale, String field, String id) {
        return scale.getAsJsonArray(field).asList().stream().map(JsonElement::getAsJsonObject)
                .filter(change -> id.equals(change.get("metric").getAsString())).findFirst().orElseThrow();
    }

    @Test
    void actualSeed63HasNoFirstHitButRecordsLargerGroupsAndMoreGroupInput() throws Exception {
        JsonObject difference = difference(fixture());
        assertTrue(difference.getAsJsonObject("query_stages").getAsJsonArray("added").isEmpty());
        JsonObject size = change(scale(difference), "bucket_transitions", "groups_before_having.max_size");
        assertEquals("2", size.getAsJsonObject("before").get("count").getAsString());
        assertEquals("6", size.getAsJsonObject("after").get("count").getAsString());
        assertEquals("2-3", size.getAsJsonObject("before").get("bucket").getAsString());
        assertEquals("4-7", size.getAsJsonObject("after").get("bucket").getAsString());
        JsonObject input = change(scale(difference), "bucket_transitions", "groups_before_having.input_rows");
        assertEquals("27", input.getAsJsonObject("before").get("count").getAsString());
        assertEquals("45", input.getAsJsonObject("after").get("count").getAsString());
        TiDBMixedSupport.save(Path.of("target/mixed-preparation/seed63-scale-change.json"), difference);
    }

    @Test
    void withinBucketChangesAndDecreasesAreReportedWithoutInventingNewScenes() throws Exception {
        JsonObject fixture = fixture();
        observation(fixture.getAsJsonObject("after"), "groups_before_having").getAsJsonObject("value")
                .getAsJsonArray("rows").get(0).getAsJsonArray().get(3).getAsJsonObject().addProperty("value", "3");
        JsonObject result = scale(difference(fixture));
        assertEquals("3", change(result, "changes", "groups_before_having.max_size").getAsJsonObject("after")
                .get("count").getAsString());
        assertTrue(result.getAsJsonArray("bucket_transitions").asList().stream().noneMatch(
                entry -> entry.getAsJsonObject().get("metric").getAsString().equals("groups_before_having.max_size")));
        observation(fixture.getAsJsonObject("after"), "groups_before_having").getAsJsonObject("value")
                .getAsJsonArray("rows").get(0).getAsJsonArray().get(3).getAsJsonObject().addProperty("value", "1");
        assertTrue(scale(difference(fixture)).getAsJsonArray("decreased")
                .contains(new com.google.gson.JsonPrimitive("groups_before_having.max_size")));
        assertEquals("0", TiDBMixedDataScale.bucket(BigInteger.ZERO));
        assertEquals("1", TiDBMixedDataScale.bucket(BigInteger.ONE));
        assertEquals("8-15", TiDBMixedDataScale.bucket(BigInteger.valueOf(8)));
        assertEquals("18446744073709551616-36893488147419103231",
                TiDBMixedDataScale.bucket(new BigInteger("18446744073709551616")));
    }

    @Test
    void sampledStaleAndDifferentQueryEvidenceNeverProvesScaleGrowth() throws Exception {
        for (String mutation : List.of("sampled", "stale", "incomplete", "different_query")) {
            JsonObject fixture = fixture();
            JsonObject before = fixture.getAsJsonObject("before");
            JsonObject fact = observation(before, "groups_before_having");
            if (mutation.equals("sampled")) {
                fact.addProperty("status", "sampled");
            } else if (mutation.equals("stale")) {
                fact.addProperty("data_revision", 99);
            } else if (mutation.equals("incomplete")) {
                fact.getAsJsonObject("value").addProperty("complete", false);
            } else {
                before.addProperty("query_id", "other_query");
            }
            JsonObject result = scale(difference(fixture));
            assertFalse(result.get("complete").getAsBoolean());
            assertTrue(result.getAsJsonArray("unverified")
                    .contains(new com.google.gson.JsonPrimitive("groups_before_having.max_size")));
            assertTrue(result.getAsJsonArray("changes").asList().stream().noneMatch(entry -> entry.getAsJsonObject()
                    .get("metric").getAsString().equals("groups_before_having.max_size")));
        }
    }

    @Test
    void realJoinMeasurementsSeparateRepeatedInputScaleFromQueryPairScale() throws Exception {
        try (MixedObservationTest.Fixture fixture = new MixedObservationTest.Fixture()) {
            JsonObject before = measured(fixture, 0);
            try (java.sql.Statement statement = fixture.database.createStatement()) {
                statement.execute("INSERT INTO t1 VALUES(1),(1)");
            }
            JsonObject after = measured(fixture, 1);
            JsonObject change = TiDBMixedDataCoverage.difference(TiDBMixedDataCoverage.snapshot(before),
                    TiDBMixedDataCoverage.snapshot(after));
            assertTrue(change.getAsJsonObject("query_stages").getAsJsonArray("added").isEmpty());
            JsonObject repeated = change(change.getAsJsonObject("scale").getAsJsonObject("base_data"),
                    "bucket_transitions", "j0_right_repeated.count");
            assertEquals("2", repeated.getAsJsonObject("before").get("count").getAsString());
            assertEquals("4", repeated.getAsJsonObject("after").get("count").getAsString());
            JsonObject pairs = change(scale(change), "bucket_transitions", "j0_pairs_after.count");
            assertEquals("4", pairs.getAsJsonObject("before").get("count").getAsString());
            assertEquals("8", pairs.getAsJsonObject("after").get("count").getAsString());
        }
    }

    private static JsonObject measured(MixedObservationTest.Fixture fixture, long revision) throws Exception {
        TiDBMixedObservations facts = new TiDBMixedObservations(
                fixture.query(sqlancer.tidb.ast.TiDBJoin.JoinType.INNER), "q", 0, revision);
        facts.collect(fixture.io, facts.allIds(), new TiDBMixedObservations.Budget(100, 64, 10_000));
        JsonObject packet = new JsonObject();
        packet.addProperty("query_id", "q");
        packet.addProperty("schema_revision", 0);
        packet.addProperty("data_revision", revision);
        packet.add("observations", facts.observations);
        packet.add("available_observations", facts.available());
        return packet;
    }

    @Test
    void benchmarkSeparatesScaleExpansionFromBooleanSceneHitsAndUnknownGains() throws Exception {
        JsonObject difference = difference(fixture());
        JsonObject round = MixedAppendTest.json("{\"validation_status\":\"accepted\","
                + "\"decision\":{\"decision\":\"append_rows\"},\"writes\":[{\"status\":\"success\"}],"
                + "\"evaluation\":{\"outcome\":\"achieved\"}}");
        round.add("data_coverage_change", difference);
        JsonArray rounds = new JsonArray();
        rounds.add(round);
        JsonObject preparation = new JsonObject();
        preparation.add("rounds", rounds);
        JsonObject report = MixedAppendTest
                .json("{\"strategy\":\"AI\",\"status\":\"COMPLETED\",\"elapsed_ms\":1,\"api_audit\":[]}");
        report.add("preparation", preparation);
        report.add("data_coverage_change", difference);
        JsonArray reports = new JsonArray();
        reports.add(report);
        JsonObject metrics = TiDBMixedBenchmarkMetrics.summarize(reports).getAsJsonObject("AI");
        assertEquals(0, metrics.get("new_observed_scene_hits").getAsInt());
        assertEquals(1, metrics.get("case_rows_with_query_scale_expansion").getAsInt());
        assertEquals(1, metrics.get("scale_expanding_append_rounds").getAsInt());
        assertTrue(metrics.getAsJsonObject("query_stage_scale").get("increased_metrics").getAsInt() > 0);
        assertEquals(0, metrics.get("productive_append_rounds").getAsInt()); // Existing first-hit definition.
        difference.remove("scale");
        metrics = TiDBMixedBenchmarkMetrics.summarize(reports).getAsJsonObject("AI");
        assertEquals(1, metrics.get("case_rows_scale_gain_unverified").getAsInt());
        assertEquals(0, metrics.get("case_rows_with_query_scale_expansion").getAsInt());
    }
}
