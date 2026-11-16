package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.Randomly;
import sqlancer.tidb.ast.TiDBExpression;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.ast.TiDBSelect;
import sqlancer.tidb.ast.TiDBTableReference;
import sqlancer.tidb.mixed.TiDBMixedSupport.Config;
import sqlancer.tidb.mixed.TiDBMixedSupport.Run;

class MixedCoverageTest {
    @TempDir
    Path temporary;

    private TiDBTableReference table(int index) {
        return new TiDBTableReference(new MixedUnitTest.FakeState().getSchema().getDatabaseTables().get(index));
    }

    private TiDBSelect query(TiDBExpression... joins) {
        TiDBSelect query = new TiDBSelect();
        query.setFromList(List.of());
        query.setJoinList(List.of(joins));
        return query;
    }

    private List<Run> runs(List<Config> configs) {
        List<Run> runs = new ArrayList<>();
        for (Config config : configs) {
            Run run = new Run(config, "SELECT 1");
            run.status = "OK";
            run.columns.add("c:INT");
            run.bag.put(List.of("N:1"), 1L);
            runs.add(run);
        }
        return runs;
    }

    private JsonObject json(Map<String, Object> snapshot) {
        return JsonParser.parseString(new Gson().toJson(snapshot)).getAsJsonObject();
    }

    private JsonObject ratio(JsonObject snapshot, int total, int flash) {
        return snapshot.getAsJsonObject("ratios")
                .getAsJsonObject("tables=" + total + ";tikv=" + (total - flash) + ";tiflash=" + flash);
    }

    private JsonObject join(JsonObject snapshot, int total, String type, String left, String right) {
        return snapshot.getAsJsonObject("joins")
                .getAsJsonObject("tables=" + total + ";type=" + type + ";left=" + left + ";right=" + right);
    }

    @Test
    void allFourTableAssignmentsExposeBalancedRatiosAndEveryOrientedJoinCombination() {
        TiDBSelect query = query(TiDBJoin.createLeftOuterJoin(table(0), table(1), null));
        TiDBMixedCoverage coverage = new TiDBMixedCoverage();
        JsonObject snapshot = json(coverage.record(query,
                runs(TiDBMixedSupport.enumerateConfigurations(List.of("t0", "t1", "t2", "t3")))));
        int[] expected = { 1, 4, 6, 4, 1 };
        for (int flash = 0; flash <= 4; flash++) {
            assertEquals(expected[flash], ratio(snapshot, 4, flash).get("attempted").getAsLong());
            assertEquals(expected[flash], ratio(snapshot, 4, flash).get("valid").getAsLong());
        }
        for (String left : List.of("tikv", "tiflash")) {
            for (String right : List.of("tikv", "tiflash")) {
                assertEquals(4L, join(snapshot, 4, "LEFT", left, right).get("valid").getAsLong());
            }
        }
        assertEquals(16L, snapshot.getAsJsonObject("paths").get("valid").getAsLong());
        assertEquals(0L, snapshot.getAsJsonObject("pathsWithoutExplicitJoin").get("attempted").getAsLong());
    }

    @Test
    void failedPlansAndExecutionErrorsAreAttemptsButNotValidCoverage() {
        List<Run> runs = runs(TiDBMixedSupport.configurations(List.of("t0", "t1")));
        runs.get(0).status = "EXECUTION_ERROR";
        runs.get(2).status = "INVALID_CONFIGURATION";
        String verdict = TiDBMixedSupport.compare(runs).status;
        TiDBMixedCoverage coverage = new TiDBMixedCoverage();
        JsonObject snapshot = json(coverage.record(query(TiDBJoin.createInnerJoin(table(0), table(1), null)), runs));
        JsonObject total = snapshot.getAsJsonObject("paths");
        assertEquals(4L, total.get("attempted").getAsLong());
        assertEquals(2L, total.get("valid").getAsLong());
        assertEquals(2L, total.get("failed").getAsLong());
        assertEquals(1L, total.getAsJsonObject("statuses").get("EXECUTION_ERROR").getAsLong());
        assertEquals(1L, total.getAsJsonObject("statuses").get("INVALID_CONFIGURATION").getAsLong());
        assertEquals(0L, ratio(snapshot, 2, 0).get("valid").getAsLong());
        assertEquals(1L, ratio(snapshot, 2, 1).get("valid").getAsLong());
        assertEquals(verdict, TiDBMixedSupport.compare(runs).status);
    }

    @Test
    void zeroBucketsMakeUnobservedRatiosAndJoinCombinationsVisible() {
        TiDBMixedCoverage coverage = new TiDBMixedCoverage();
        Run baseline = runs(List.of(new Config(Map.of("t0", "tikv", "t1", "tikv", "t2", "tikv", "t3", "tikv")))).get(0);
        JsonObject snapshot = json(
                coverage.record(query(TiDBJoin.createInnerJoin(table(0), table(1), null)), List.of(baseline)));
        assertEquals(5, snapshot.getAsJsonObject("ratios").size());
        assertEquals(4, snapshot.getAsJsonObject("joins").size());
        assertEquals(0L, ratio(snapshot, 4, 2).get("attempted").getAsLong());
        assertEquals(0L, join(snapshot, 4, "INNER", "tikv", "tiflash").get("valid").getAsLong());
    }

    @Test
    void joinDirectionAndNaturalJoinSubtypeRemainDistinct() {
        TiDBMixedCoverage coverage = new TiDBMixedCoverage();
        List<Run> runs = runs(List.of(new Config(Map.of("t0", "tikv", "t1", "tiflash"))));
        coverage.record(query(TiDBJoin.createLeftOuterJoin(table(0), table(1), null)), runs);
        coverage.record(query(TiDBJoin.createLeftOuterJoin(table(1), table(0), null)), runs);
        coverage.record(query(TiDBJoin.createRightOuterJoin(table(0), table(1), null)), runs);
        coverage.record(query(TiDBJoin.createNaturalJoin(table(0), table(1), TiDBJoin.NaturalJoinType.LEFT)), runs);
        JsonObject snapshot = json(coverage.snapshot());
        assertEquals(1L, join(snapshot, 2, "LEFT", "tikv", "tiflash").get("valid").getAsLong());
        assertEquals(1L, join(snapshot, 2, "LEFT", "tiflash", "tikv").get("valid").getAsLong());
        assertEquals(1L, join(snapshot, 2, "RIGHT", "tikv", "tiflash").get("valid").getAsLong());
        assertEquals(1L, join(snapshot, 2, "NATURAL_LEFT", "tikv", "tiflash").get("valid").getAsLong());
        assertEquals(4L, snapshot.getAsJsonObject("paths").get("valid").getAsLong());
    }

    @Test
    void multipleJoinNodesCountOccurrencesWithoutMultiplyingPathCounts() {
        TiDBMixedCoverage coverage = new TiDBMixedCoverage();
        List<Run> runs = runs(List.of(new Config(Map.of("t0", "tikv", "t1", "tikv", "t2", "tikv", "t3", "tikv"))));
        JsonObject snapshot = json(coverage.record(query(TiDBJoin.createInnerJoin(table(0), table(1), null),
                TiDBJoin.createInnerJoin(table(2), table(3), null)), runs));
        assertEquals(1L, snapshot.getAsJsonObject("paths").get("valid").getAsLong());
        assertEquals(1L, ratio(snapshot, 4, 0).get("valid").getAsLong());
        assertEquals(2L, join(snapshot, 4, "INNER", "tikv", "tikv").get("valid").getAsLong());
    }

    @Test
    void nestedJoinSidesCanContainMixedEngines() {
        TiDBJoin child = TiDBJoin.createInnerJoin(table(0), table(1), null);
        TiDBSelect query = query(TiDBJoin.createLeftOuterJoin(child, table(2), null));
        List<Run> runs = runs(List.of(new Config(Map.of("t0", "tikv", "t1", "tiflash", "t2", "tikv"))));
        JsonObject snapshot = json(new TiDBMixedCoverage().record(query, runs));
        assertEquals(1L, join(snapshot, 3, "LEFT", "mixed", "tikv").get("valid").getAsLong());
        assertEquals(1L, join(snapshot, 3, "INNER", "tikv", "tiflash").get("valid").getAsLong());
        assertFalse(snapshot.getAsJsonObject("joins").has("tables=3;type=LEFT;left=tikv;right=mixed"));
    }

    @Test
    void commaFromListsDoNotInventExplicitJoinCoverage() {
        TiDBSelect query = query();
        query.setFromList(List.of(table(0), table(1)));
        JsonObject snapshot = json(
                new TiDBMixedCoverage().record(query, runs(TiDBMixedSupport.configurations(List.of("t0", "t1")))));
        assertTrue(snapshot.getAsJsonObject("joins").entrySet().isEmpty());
        assertEquals(4L, snapshot.getAsJsonObject("pathsWithoutExplicitJoin").get("valid").getAsLong());
        assertEquals(4L, snapshot.getAsJsonObject("paths").get("valid").getAsLong());
    }

    @Test
    void snapshotsRemainIndependentAndJsonRetainsBatchAndCumulativeCounts() throws Exception {
        TiDBMixedCoverage coverage = new TiDBMixedCoverage();
        TiDBSelect query = query(TiDBJoin.createCrossJoin(table(0), table(1), null));
        List<Run> runs = runs(TiDBMixedSupport.configurations(List.of("t0", "t1")));
        Map<String, Object> first = coverage.record(query, runs);
        Map<String, Object> oldTotal = coverage.snapshot();
        Map<String, Object> second = coverage.record(query, runs);
        assertEquals(4L, json(first).getAsJsonObject("paths").get("attempted").getAsLong());
        assertEquals(4L, json(oldTotal).getAsJsonObject("paths").get("attempted").getAsLong());
        assertEquals(4L, json(second).getAsJsonObject("paths").get("attempted").getAsLong());
        Path file = temporary.resolve("coverage.json");
        TiDBMixedSupport.save(file, coverage.snapshot());
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(8L, saved.getAsJsonObject("paths").get("attempted").getAsLong());
        assertEquals(4L, ratio(saved, 2, 1).get("valid").getAsLong());
    }

    @Test
    void observingCoverageDoesNotChangeRandomSampling() {
        List<String> tables = List.of("t0", "t1", "t2", "t3");
        TiDBSelect query = query(TiDBJoin.createInnerJoin(table(0), table(1), null));
        List<Run> runs = runs(TiDBMixedSupport.enumerateConfigurations(tables));
        new Randomly(42);
        List<String> expected = TiDBMixedSupport.configurations(tables).stream().map(c -> c.name)
                .collect(Collectors.toList());
        new Randomly(42);
        new TiDBMixedCoverage().record(query, runs);
        List<String> actual = TiDBMixedSupport.configurations(tables).stream().map(c -> c.name)
                .collect(Collectors.toList());
        assertEquals(expected, actual);
    }
}
