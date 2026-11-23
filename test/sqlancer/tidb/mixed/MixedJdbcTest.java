package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonParser;

import sqlancer.SQLConnection;
import sqlancer.StateToReproduce;
import sqlancer.tidb.TiDBOracleFactory;
import sqlancer.tidb.TiDBProvider;
import sqlancer.tidb.ast.TiDBColumnReference;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.ast.TiDBSelect;
import sqlancer.tidb.ast.TiDBTableReference;
import sqlancer.tidb.mixed.TiDBMixedSupport.Config;
import sqlancer.tidb.mixed.TiDBMixedSupport.Run;

/** Exercises the JDBC execution boundary without pretending that fixtures are a live TiFlash cluster. */
class MixedJdbcTest {
    @TempDir
    Path temporary;

    @Test
    void capabilityNoticesNeedScanValidationAndNeverExemptExecutionOrConversionWarnings() throws Exception {
        Fixture fixture = new Fixture();
        TiDBMixedSupport io = fixture.support();
        io.beginSnapshot();
        fixture.warning = new SQLWarning(
                "Aggregation can not be pushed to tiflash because AggFunc `bit_and` is not supported now", "HY000",
                1105);
        fixture.explainWarningOnly = true;
        Config config = new Config(Map.of("t0", "tikv", "t1", "tiflash"));
        assertEquals("OK", io.execute("SELECT c FROM t0,t1", config, false).status);
        assertTrue(io.events.stream().anyMatch(event -> "PLAN_CAPABILITY_NOTICE".equals(event.get("stage"))));
        fixture.invalidPlan = true;
        assertEquals("INVALID_CONFIGURATION", io.execute("SELECT c FROM t0,t1", config, false).status);
        assertFalse(TiDBMixedSupport.planningNotice("SELECT 1", fixture.warning));
        assertFalse(TiDBMixedSupport.planningNotice("EXPLAIN SELECT 1",
                new SQLWarning("Truncated incorrect INTEGER value: 'A'", "22007", 1292)));
        fixture.invalidPlan = false;
        fixture.warning.setNextWarning(new SQLWarning("hint ignored", "HY000", 1815));
        assertEquals("INCONCLUSIVE", io.execute("SELECT c FROM t0,t1", config, false).status);
    }

    @Test
    void floatMetadataAndPlansWithoutStorageAreRejectedBeforePreparation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.resultType = Types.DOUBLE;
        TiDBMixedSupport io = fixture.support();
        TiDBMixedSupport.Rejected rejected = assertThrows(TiDBMixedSupport.Rejected.class,
                () -> io.inspectCandidate("SELECT c FROM t0,t1"));
        assertEquals("UNSUPPORTED_EXACT_RESULT_TYPE", rejected.status);
        assertTrue(fixture.queries.stream().anyMatch(sql -> sql.endsWith("LIMIT 0")));
        assertThrows(TiDBMixedSupport.Rejected.class, () -> TiDBMixedSupport
                .requireScannedCandidate(List.of(Map.of("id", "TableDual", "access object", ""))));
        TiDBMixedSupport.requireScannedCandidate(List.of(Map.of("id", "TableFullScan", "access object", "table:t0")));
    }

    @Test
    void incompatibleOracleOptionsFailBeforeConnectingOrChangingTheDatabase() {
        MixedUnitTest.FakeState state = new MixedUnitTest.FakeState();
        state.getDbmsSpecificOptions().oracle = List.of(TiDBOracleFactory.AI_MIXED_STORAGE, TiDBOracleFactory.WHERE);
        assertThrows(IllegalArgumentException.class, () -> new TiDBProvider().createDatabase(state));
        state.getDbmsSpecificOptions().oracle = List.of(TiDBOracleFactory.AI_MIXED_STORAGE);
        state.getDbmsSpecificOptions().nonPreparePlanCache = true;
        assertThrows(IllegalArgumentException.class, () -> new TiDBProvider().createDatabase(state));
    }

    @Test
    void sourceIdentityAcceptsLocalReleaseAliasButRejectsOtherRevisions() {
        TiDBMixedTarget.validate(
                "Release Version: " + TiDBMixedTarget.VERSION + "\nGit Commit Hash: " + TiDBMixedTarget.COMMIT);
        TiDBMixedTarget.validate("Release Version: v9.0.0-latest-lwm\nGit Commit Hash: b1d4142f99\nEdition: Community");
        assertThrows(TiDBMixedSupport.Rejected.class,
                () -> TiDBMixedTarget.validate("Release Version: " + TiDBMixedTarget.VERSION));
        assertThrows(TiDBMixedSupport.Rejected.class,
                () -> TiDBMixedTarget.validate("Git Commit Hash: dd8253cf5da931a8dd5c8eceaafe84748df06c2d"));
        assertThrows(TiDBMixedSupport.Rejected.class,
                () -> TiDBMixedTarget.validate("Git Commit Hash: " + TiDBMixedTarget.COMMIT + "000"));
    }

    @Test
    void duplicateOrIncompatibleAssignmentsCannotPass() {
        List<Run> runs = successfulRuns();
        runs.set(1, runs.get(0));
        assertEquals("INVALID_CONFIGURATION", TiDBMixedSupport.compare(runs).status);
        runs = successfulRuns();
        runs.set(3, runs.get(2));
        assertEquals("INVALID_CONFIGURATION", TiDBMixedSupport.compare(runs).status);
        assertThrows(IllegalArgumentException.class, () -> TiDBMixedSupport.configurations(List.of("t0")));
        assertThrows(IllegalArgumentException.class, () -> TiDBMixedSupport.configurations(List.of("t0", "t0")));
        assertThrows(IllegalArgumentException.class, () -> new Config(Map.of("t0", "root")));
        assertThrows(IllegalArgumentException.class, () -> new Config(Map.of()));
    }

    private List<Run> successfulRuns() {
        List<Run> runs = new ArrayList<>();
        for (Config config : TiDBMixedSupport.configurations(List.of("t0", "t1"))) {
            Run run = new Run(config, "SELECT 1");
            run.snapshot = "123456789";
            run.status = "OK";
            run.columns.add("c:DECIMAL");
            runs.add(run);
        }
        return runs;
    }

    @Test
    void emptyBagsMetadataAndPartialFailuresKeepDistinctVerdicts() {
        List<Run> runs = successfulRuns();
        assertEquals("PASS", TiDBMixedSupport.compare(runs).status);
        runs.get(2).columns.set(0, "c:VARCHAR");
        assertEquals("MIXED_VS_SINGLE_MISMATCH", TiDBMixedSupport.compare(runs).status);
        runs = successfulRuns();
        runs.get(2).status = "EXECUTION_ERROR";
        assertEquals("EXECUTION_ERROR", TiDBMixedSupport.compare(runs).status);
        runs.get(0).bag.put(List.of("NULL"), 1L);
        assertEquals("BASELINE_DISAGREEMENT", TiDBMixedSupport.compare(runs).status);
        assertFalse(TiDBMixedSupport.compare(runs).failures.isEmpty());
    }

    @Test
    void cellEncodingRetainsNullBinaryAndExactDecimalValues() throws Exception {
        try (ResultSet rows = rows(new String[] { "n", "s", "b", "d" },
                new int[] { Types.INTEGER, Types.VARCHAR, Types.VARBINARY, Types.DECIMAL },
                List.<Object[]> of(new Object[] { null, "NULL", new byte[] { 0, -1 },
                        new java.math.BigDecimal("123456789012345678901234567890.01") }))) {
            assertTrue(rows.next());
            assertEquals("NULL", TiDBMixedSupport.cell(rows, 1, Types.INTEGER, false));
            assertEquals("S:NULL", TiDBMixedSupport.cell(rows, 2, Types.VARCHAR, false));
            assertEquals("B:AP8=", TiDBMixedSupport.cell(rows, 3, Types.VARBINARY, false));
            assertEquals("N:123456789012345678901234567890.01", TiDBMixedSupport.cell(rows, 4, Types.DECIMAL, false));
        }
    }

    @Test
    void fourPathsUseOneSnapshotAndValidateBothPlansBeforeReadingResults() throws Exception {
        Fixture fixture = new Fixture();
        TiDBMixedSupport io = fixture.support();
        io.beginSnapshot();
        List<Run> runs = io.executeAll("SELECT t0.c FROM t0 JOIN t1 ON t0.c=t1.c",
                TiDBMixedSupport.configurations(List.of("t0", "t1")));
        assertEquals("PASS", TiDBMixedSupport.compare(runs).status);
        assertTrue(runs.stream().allMatch(r -> "123456789".equals(r.snapshot)));
        assertTrue(runs.stream().allMatch(r -> r.rows.size() == 2 && r.bag.get(List.of("N:1")) == 2));
        assertEquals(4, fixture.queries.stream().filter(s -> s.startsWith("EXPLAIN FORMAT")).count());
        assertEquals(4, fixture.queries.stream().filter(s -> s.startsWith("EXPLAIN ANALYZE")).count());
        assertEquals(4, fixture.streams);
        assertEquals(1, fixture.queries.stream().filter(s -> s.contains("tidb_snapshot='123456789'")).count());
        io.reset();
        assertEquals("", io.snapshot);
        assertTrue(fixture.queries.contains("SET SESSION tidb_snapshot=''"));
    }

    @Test
    void successfulResultsWithZeroOrDifferentSnapshotsCannotBeCompared() {
        for (String invalid : List.of("", "0", "123456788")) {
            List<Run> runs = successfulRuns();
            runs.get(3).snapshot = invalid;
            runs.get(3).bag.put(List.of("N:2"), 1L);
            TiDBMixedSupport.Outcome outcome = TiDBMixedSupport.compare(runs);
            assertEquals("SNAPSHOT_ERROR", outcome.status);
            assertFalse(outcome.allPathsValid);
            assertFalse(outcome.mismatch());
            assertTrue(outcome.comparisons.isEmpty());
        }
    }

    @Test
    void snapshotAcquisitionEndsItsTransactionBeforeSettingAndVerifyingHistoricalReads() throws Exception {
        Fixture fixture = new Fixture();
        TiDBMixedSupport io = fixture.support();
        io.beginSnapshot();
        int begin = fixture.queries.indexOf("BEGIN");
        int acquire = fixture.queries.indexOf("SELECT TIDB_CURRENT_TSO() AS tso");
        int rollback = fixture.queries.indexOf("ROLLBACK");
        int setting = fixture.queries.indexOf("SET SESSION tidb_snapshot='123456789'");
        assertTrue(begin < acquire && acquire < rollback && rollback < setting);
        assertTrue(fixture.queries.get(setting + 1).startsWith("SELECT @@tidb_snapshot"));
        assertFalse(fixture.active);
        assertEquals("123456789", io.environment.get("snapshotTSO"));
        assertTrue(io.events.stream()
                .anyMatch(e -> "SNAPSHOT_CHECK".equals(e.get("stage")) && "VERIFIED".equals(e.get("status"))));
    }

    @Test
    void invalidTransactionTsosAreRejectedAndCannotProducePasses() {
        List<String> invalid = new ArrayList<>(List.of("0", "-1", "01", " 1", "abc", "18446744073709551616"));
        invalid.add(null);
        for (String tso : invalid) {
            Fixture fixture = new Fixture();
            fixture.transactionTso = tso;
            TiDBMixedSupport io = fixture.support();
            assertEquals("SNAPSHOT_ERROR", assertThrows(TiDBMixedSupport.Rejected.class, io::beginSnapshot).status);
            assertEquals("", io.snapshot);
            assertFalse(io.environment.containsKey("snapshotTSO"));
            assertFalse(fixture.active);
            assertTrue(fixture.queries.contains("ROLLBACK"));
            assertFalse(fixture.queries.stream().anyMatch(q -> q.startsWith("SET SESSION tidb_snapshot=")));
            assertEquals("SNAPSHOT_ERROR", TiDBMixedSupport.compare(io.executeAll("SELECT t0.c FROM t0,t1",
                    TiDBMixedSupport.configurations(List.of("t0", "t1")))).status);
            assertEquals(0, fixture.streams);
        }
        assertTrue(TiDBMixedSupport.positiveTso("1"));
        assertTrue(TiDBMixedSupport.positiveTso("18446744073709551615"));
    }

    @Test
    void snapshotAcquisitionSqlFailuresNeverPublishASnapshot() {
        for (String sql : List.of("BEGIN", "SELECT TIDB_CURRENT_TSO() AS tso", "ROLLBACK",
                "SET SESSION tidb_snapshot='123456789'",
                "SELECT @@tidb_snapshot AS snapshot, @@tidb_current_ts AS transaction_ts, @@autocommit AS autocommit")) {
            Fixture fixture = new Fixture();
            fixture.failSql = sql;
            TiDBMixedSupport io = fixture.support();
            assertEquals("SNAPSHOT_ERROR", assertThrows(TiDBMixedSupport.Rejected.class, io::beginSnapshot).status);
            assertEquals("", io.snapshot);
            assertFalse(io.environment.containsKey("snapshotTSO"));
            assertFalse(fixture.queries.contains("EXPLAIN ANALYZE SELECT t0.c FROM t0,t1"));
            if (sql.equals("ROLLBACK")) {
                assertFalse(fixture.queries.stream().anyMatch(q -> q.startsWith("SET SESSION tidb_snapshot=")));
            }
        }
    }

    @Test
    void ignoredSnapshotSettingAndExistingTransactionsAreRejected() {
        Fixture fixture = new Fixture();
        fixture.ignoreSnapshotSetting = true;
        TiDBMixedSupport io = fixture.support();
        assertEquals("SNAPSHOT_ERROR", assertThrows(TiDBMixedSupport.Rejected.class, io::beginSnapshot).status);
        assertEquals("", io.snapshot);
        assertTrue(fixture.queries.contains("SET SESSION tidb_snapshot=''"));
        Fixture busy = new Fixture();
        busy.active = true;
        assertEquals("SNAPSHOT_ERROR",
                assertThrows(TiDBMixedSupport.Rejected.class, () -> busy.support().beginSnapshot()).status);
        assertFalse(busy.queries.contains("BEGIN"));
        assertFalse(busy.queries.contains("ROLLBACK"));
    }

    @Test
    void snapshotDriftBeforeOrDuringAPathCannotPassOrHideAsPreflightFailure() throws Exception {
        for (boolean during : List.of(false, true)) {
            Fixture fixture = new Fixture();
            TiDBMixedSupport io = fixture.support();
            io.beginSnapshot();
            if (during) {
                fixture.driftOnResult = true;
            } else {
                fixture.sessionSnapshot = "0";
            }
            List<Run> runs = io.executeAll("SELECT t0.c FROM t0,t1",
                    TiDBMixedSupport.configurations(List.of("t0", "t1")));
            assertTrue(runs.stream().allMatch(r -> r.status.equals("SNAPSHOT_ERROR")));
            assertEquals("SNAPSHOT_ERROR", TiDBMixedSupport.compare(runs).status);
            assertEquals(during ? 1 : 0, fixture.streams);
            assertEquals("SNAPSHOT_ERROR", assertThrows(TiDBMixedSupport.Rejected.class, () -> io
                    .preflight(TiDBMixedGeneration.baseTables(new MixedUnitTest.FakeState()).subList(0, 2))).status);
        }
    }

    @Test
    void invalidPlanStopsBeforeAnalyzeAndResultExecution() throws Exception {
        Fixture fixture = new Fixture();
        fixture.invalidPlan = true;
        TiDBMixedSupport io = fixture.support();
        io.beginSnapshot();
        Run run = io.execute("SELECT t0.c FROM t0,t1", new Config(Map.of("t0", "tikv", "t1", "tiflash")), false);
        assertEquals("INVALID_CONFIGURATION", run.status);
        assertEquals(0, fixture.streams);
        assertFalse(fixture.queries.stream().anyMatch(s -> s.startsWith("EXPLAIN ANALYZE")));
    }

    @Test
    void baselineExecutionErrorsPreserveMixedDisagreementInJsonEvidence() throws Exception {
        Fixture fixture = new Fixture();
        fixture.mixedOnlyDisagreement = true;
        TiDBMixedSupport io = fixture.support();
        io.beginSnapshot();
        List<Run> runs = io.executeAll("SELECT t0.c FROM t0,t1", TiDBMixedSupport.configurations(List.of("t0", "t1")));
        TiDBMixedSupport.Outcome outcome = TiDBMixedSupport.compare(runs);
        assertEquals("MIXED_VS_MIXED_MISMATCH", outcome.status);
        assertTrue(outcome.mismatch());
        assertFalse(outcome.allPathsValid);
        assertEquals(2, runs.stream().filter(r -> r.status.equals("OK")).count());
        assertEquals(1, outcome.comparisons.size());
        assertEquals(2, outcome.failures.size());
        assertTrue(runs.stream().allMatch(r -> r.snapshot.equals(io.snapshot)));
        Path file = temporary.resolve("mixed-disagreement.json");
        TiDBMixedSupport.save(file, Map.of("outcome", outcome, "runs", runs));
        com.google.gson.JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject()
                .getAsJsonObject("outcome");
        assertEquals("MIXED_VS_MIXED_MISMATCH", saved.get("status").getAsString());
        assertFalse(saved.get("allPathsValid").getAsBoolean());
        assertEquals(1, saved.getAsJsonArray("differingPairs").size());
        assertEquals(2, saved.getAsJsonArray("failures").size());
        assertEquals("MIXED_VS_MIXED_MISMATCH", saved.getAsJsonArray("mismatchTypes").get(0).getAsString());
    }

    @Test
    void expandedConfigurationsExecuteEverySelectedPathWithOneSnapshot() throws Exception {
        for (int tableCount : List.of(3, 4, 5)) {
            Fixture fixture = new Fixture();
            fixture.tableCount = tableCount;
            TiDBMixedSupport io = fixture.support();
            io.beginSnapshot();
            List<String> tables = java.util.stream.IntStream.range(0, tableCount).mapToObj(i -> "t" + i)
                    .collect(Collectors.toList());
            List<Run> runs = io.executeAll("SELECT t0.c FROM " + String.join(",", tables),
                    TiDBMixedSupport.configurations(tables));
            int expected = tableCount == 3 ? 8 : 6;
            assertEquals(expected, runs.size());
            assertEquals(expected, fixture.streams);
            assertEquals("PASS", TiDBMixedSupport.compare(runs).status);
            assertEquals(expected * (expected - 1) / 2, TiDBMixedSupport.compare(runs).comparisons.size());
            assertTrue(runs.stream().allMatch(r -> r.snapshot.equals(io.snapshot)));
        }
    }

    @Test
    void executionCoverageKeepsPrimaryAndAlternativePlanOutcomesSeparate() throws Exception {
        MixedUnitTest.FakeState state = new MixedUnitTest.FakeState();
        List<sqlancer.tidb.TiDBSchema.TiDBTable> tables = TiDBMixedGeneration.baseTables(state).subList(0, 2);
        TiDBSelect ast = new TiDBSelect();
        ast.setFetchColumns(List.of(new TiDBColumnReference(tables.get(0).getColumns().get(0))));
        ast.setFromList(List.of());
        ast.setJoinList(List.of(TiDBJoin.createCrossJoin(new TiDBTableReference(tables.get(0)),
                new TiDBTableReference(tables.get(1)), null)));
        TiDBMixedGeneration.Candidate query = new TiDBMixedGeneration.Candidate(ast, tables, List.of());
        Fixture fixture = new Fixture();
        TiDBMixedSupport io = fixture.support();
        io.beginSnapshot();
        TiDBAiMixedOracle oracle = new TiDBAiMixedOracle(state);
        List<Config> configs = TiDBMixedSupport.configurations(List.of("t0", "t1"));
        Map<String, Object> record = new LinkedHashMap<>();
        List<Run> primary = oracle.executeWithCoverage(io, query, configs, record, "coverage");
        assertEquals("PASS", TiDBMixedSupport.compare(primary).status);
        fixture.invalidPlan = true;
        List<Run> alternative = oracle.executeWithCoverage(io, query, configs, record, "alternativeCoverage");
        assertTrue(alternative.stream().allMatch(r -> r.status.equals("INVALID_CONFIGURATION")));
        Path file = temporary.resolve("coverage-evidence.json");
        TiDBMixedSupport.save(file, record);
        com.google.gson.JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(4L, saved.getAsJsonObject("coverage").getAsJsonObject("paths").get("valid").getAsLong());
        com.google.gson.JsonObject failed = saved.getAsJsonObject("alternativeCoverage");
        assertEquals(4L, failed.getAsJsonObject("paths").get("attempted").getAsLong());
        assertEquals(0L, failed.getAsJsonObject("paths").get("valid").getAsLong());
        assertEquals(4L,
                failed.getAsJsonObject("paths").getAsJsonObject("statuses").get("INVALID_CONFIGURATION").getAsLong());
        assertEquals(0L, failed.getAsJsonObject("joins").getAsJsonObject("tables=2;type=CROSS;left=tikv;right=tiflash")
                .get("valid").getAsLong());
    }

    @Test
    void unavailableModelStillReachesExportSnapshotPreflightAndAllDifferentialPaths() throws Exception {
        for (boolean initializationFailure : List.of(false, true)) {
            try (MixedAppendTest.Fixture data = new MixedAppendTest.Fixture()) {
                TiDBMixedGeneration.Candidate query = MixedPreparationTest.query(data, false);
                TiDBMixedPreparationHarness harness = new TiDBMixedPreparationHarness(data.state, query,
                        MixedPreparationTest.packet(data, query), List.of(), 100, 64, 2);
                Map<String, Object> record = new LinkedHashMap<>();
                TiDBAiMixedOracle.prepareData(harness, () -> {
                    if (initializationFailure) {
                        return TiDBDeepSeekPreparationClient.fromEnvironment(3, Map.of());
                    }
                    return new TiDBDeepSeekPreparationClient(body -> {
                        throw new java.io.IOException("injected model outage");
                    }, "fixture", 3, 100, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                            java.math.BigDecimal.ZERO);
                }, record);
                // JDBC fixtures verify the engine workflow; they do not simulate physical TiFlash storage.
                Fixture fixture = new Fixture();
                TiDBMixedSupport io = fixture.support();
                record.put("restoreStatements", io.dump(query.tables));
                io.beginSnapshot();
                TiDBAiMixedOracle oracle = new TiDBAiMixedOracle(data.state);
                assertTrue(oracle.preflightReady(io, query.tables, record));
                List<Run> runs = oracle.executeWithCoverage(io, query,
                        TiDBMixedSupport.configurations(List.of("t0", "t1")), record, "coverage");
                assertEquals(4, runs.size());
                TiDBMixedSupport.Outcome outcome = TiDBMixedSupport.compare(runs);
                assertEquals("PASS", outcome.status);
                assertEquals(6, outcome.comparisons.size());
                assertFalse(((List<?>) record.get("restoreStatements")).isEmpty());
                assertEquals("123456789", io.snapshot);
                assertEquals("1", data.scalar("SELECT COUNT(*) FROM t0"));
                assertEquals("0", data.scalar("SELECT COUNT(*) FROM t1"));
                TiDBMixedSupport.save(temporary.resolve("unavailable-model-" + initializationFailure + ".json"),
                        record);
            }
        }
    }

    @Test
    void preflightDisagreementStopsTheRoundAndHasSeparateCountersAndEvidence() throws Exception {
        Fixture fixture = new Fixture();
        fixture.preflightMismatch = true;
        TiDBMixedSupport io = fixture.support();
        io.beginSnapshot();
        TiDBAiMixedOracle oracle = new TiDBAiMixedOracle(new MixedUnitTest.FakeState());
        List<sqlancer.tidb.TiDBSchema.TiDBTable> tables = TiDBMixedGeneration.baseTables(new MixedUnitTest.FakeState())
                .subList(0, 2);
        Map<String, Object> record = new LinkedHashMap<>();
        assertFalse(oracle.preflightReady(io, tables, record));
        assertEquals("DATA_PREFLIGHT_MISMATCH", record.get("status"));
        assertEquals("DATA_PREFLIGHT_MISMATCH", record.get("preflightStatus"));
        assertEquals(Map.of("PASS", 0L, "DATA_PREFLIGHT_MISMATCH", 1L, "PREFLIGHT_FAILED", 0L),
                oracle.preflightCounts());
        assertEquals(2, fixture.streams); // Stop at the first differing table; no mixed query is executed.
        assertFalse(fixture.queries.stream().anyMatch(q -> q.contains("TIKV[") && q.contains("TIFLASH[")));
        Map<String, Object> event = io.events.stream().filter(e -> "PREFLIGHT".equals(e.get("stage"))).findFirst()
                .orElseThrow();
        Run kv = (Run) event.get("tikv");
        Run flash = (Run) event.get("tiflash");
        assertEquals("OK", kv.status);
        assertEquals("OK", flash.status);
        assertEquals(kv.snapshot, flash.snapshot);
        assertFalse(kv.bag.equals(flash.bag));
        Path file = temporary.resolve("preflight-mismatch.json");
        TiDBMixedSupport.save(file,
                Map.of("attempts", List.of(record), "events", io.events, "preflightCounts", oracle.preflightCounts()));
        com.google.gson.JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        assertEquals(1L, saved.getAsJsonObject("preflightCounts").get("DATA_PREFLIGHT_MISMATCH").getAsLong());
        assertFalse(saved.getAsJsonArray("attempts").get(0).getAsJsonObject().has("runs"));
        assertFalse(oracle.preflightReady(io, tables, new LinkedHashMap<>()));
        assertEquals(2L, oracle.preflightCounts().get("DATA_PREFLIGHT_MISMATCH"));
        fixture.preflightMismatch = false;
        assertTrue(oracle.preflightReady(io, tables, new LinkedHashMap<>()));
        assertEquals(1L, oracle.preflightCounts().get("PASS"));
        assertEquals(2L, oracle.preflightCounts().get("DATA_PREFLIGHT_MISMATCH"));
    }

    @Test
    void matchingPreflightAllowsComparisonAndOrdinaryFailuresAreCountedSeparately() throws Exception {
        Fixture fixture = new Fixture();
        TiDBMixedSupport io = fixture.support();
        io.beginSnapshot();
        TiDBAiMixedOracle oracle = new TiDBAiMixedOracle(new MixedUnitTest.FakeState());
        List<sqlancer.tidb.TiDBSchema.TiDBTable> tables = TiDBMixedGeneration.baseTables(new MixedUnitTest.FakeState())
                .subList(0, 2);
        Map<String, Object> record = new LinkedHashMap<>();
        assertTrue(oracle.preflightReady(io, tables, record));
        assertEquals("PASS", record.get("preflightStatus"));
        List<Run> runs = io.executeAll("SELECT t0.c FROM t0,t1", TiDBMixedSupport.configurations(List.of("t0", "t1")));
        assertEquals("PASS", TiDBMixedSupport.compare(runs).status);
        fixture.warning = new SQLWarning("fixture warning", "01000", 1815);
        TiDBMixedSupport.Rejected rejected = assertThrows(TiDBMixedSupport.Rejected.class,
                () -> oracle.preflightReady(io, tables, new LinkedHashMap<>()));
        assertEquals("PREFLIGHT_FAILED", rejected.status);
        assertEquals(Map.of("PASS", 1L, "DATA_PREFLIGHT_MISMATCH", 0L, "PREFLIGHT_FAILED", 1L),
                oracle.preflightCounts());
    }

    @Test
    void warningsErrorsAndResourceLimitsNeverBecomePasses() throws Exception {
        Fixture fixture = new Fixture();
        TiDBMixedSupport io = fixture.support();
        io.beginSnapshot();
        Config config = new Config(Map.of("t0", "tikv", "t1", "tiflash"));
        fixture.warning = new SQLWarning("hint ignored", "01000", 1815);
        assertEquals("INCONCLUSIVE", io.execute("SELECT c FROM t0,t1", config, false).status);
        fixture.warning = null;
        fixture.queryError = new SQLException("fixture timeout", "HY000", 3024);
        Run error = io.execute("SELECT c FROM t0,t1", config, false);
        assertEquals("EXECUTION_ERROR", error.status);
        assertTrue(error.error.contains("HY000:3024"));
        fixture.queryError = null;
        fixture.rowCount = 10001;
        assertEquals("INCONCLUSIVE", io.execute("SELECT c FROM t0,t1", config, false).status);
        fixture.rowCount = 0;
        fixture.resultType = Types.DOUBLE;
        assertEquals("INCONCLUSIVE", io.execute("SELECT c FROM t0,t1", config, false).status);
    }

    @Test
    void jsonEvidenceRoundTripsComplexBagKeysAndReplacesAtomically() throws Exception {
        Path file = temporary.resolve("case.json");
        TiDBMixedSupport.save(file, Map.of("runs", successfulRuns()));
        assertEquals(4, JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("runs").size());
        TiDBMixedSupport.save(file, Map.of("status", "MIXED_VS_SINGLE_MISMATCH"));
        assertEquals("MIXED_VS_SINGLE_MISMATCH",
                JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("status").getAsString());
        try (java.util.stream.Stream<Path> paths = Files.list(temporary)) {
            assertEquals(1, paths.count());
        }
    }

    private static CachedRowSet rows(String[] names, int[] types, List<Object[]> values) throws SQLException {
        CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(names.length);
        for (int i = 0; i < names.length; i++) {
            metadata.setColumnName(i + 1, names[i]);
            metadata.setColumnLabel(i + 1, names[i]);
            metadata.setColumnType(i + 1, types[i]);
            metadata.setColumnTypeName(i + 1, types[i] == Types.DOUBLE ? "DOUBLE" : "INT");
        }
        rows.setMetaData(metadata);
        for (Object[] value : values) {
            rows.moveToInsertRow();
            for (int i = 0; i < value.length; i++) {
                rows.updateObject(i + 1, value[i]);
            }
            rows.insertRow();
            rows.moveToCurrentRow();
        }
        rows.beforeFirst();
        return rows;
    }

    private static final class Fixture {
        final List<String> queries = new ArrayList<>();
        boolean active;
        String sessionSnapshot = "";
        String transactionTso = "123456789";
        String failSql;
        boolean ignoreSnapshotSetting;
        boolean driftOnResult;
        boolean invalidPlan;
        boolean mixedOnlyDisagreement;
        boolean preflightMismatch;
        int tableCount = 2;
        int streams;
        int rowCount = 2;
        int resultType = Types.INTEGER;
        SQLWarning warning;
        boolean explainWarningOnly;
        SQLException queryError;

        TiDBMixedSupport support() {
            MixedUnitTest.FakeState state = new MixedUnitTest.FakeState();
            state.setState(new StateToReproduce("mixed_fixture", new TiDBProvider()));
            Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                        if (method.getName().equals("createStatement")) {
                            return statement();
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            state.setConnection(new SQLConnection(connection));
            return new TiDBMixedSupport(state);
        }

        Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Statement.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                        case "execute":
                            String sql = (String) args[0];
                            queries.add(sql);
                            failIfRequested(sql);
                            if (sql.equals("BEGIN")) {
                                active = true;
                            } else if (sql.equals("ROLLBACK")) {
                                active = false;
                            } else if (sql.startsWith("SET SESSION tidb_snapshot=") && !ignoreSnapshotSetting) {
                                sessionSnapshot = sql.substring(sql.indexOf('=') + 2, sql.length() - 1);
                            }
                            return false;
                        case "executeQuery":
                            return query((String) args[0]);
                        case "setFetchSize":
                            assertEquals(Integer.MIN_VALUE, args[0]);
                            streams++;
                            return null;
                        case "setQueryTimeout":
                        case "close":
                            return null;
                        case "getWarnings":
                            return explainWarningOnly && !queries.get(queries.size() - 1).startsWith("EXPLAIN ") ? null
                                    : warning;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }

        void failIfRequested(String sql) throws SQLException {
            if (sql.equals(failSql)) {
                throw new SQLException("injected snapshot failure", "HY000", 1105);
            }
        }

        ResultSet query(String sql) throws SQLException {
            queries.add(sql);
            failIfRequested(sql);
            if (sql.startsWith("SELECT @@tidb_snapshot")) {
                return rows(new String[] { "snapshot", "transaction_ts", "autocommit" },
                        new int[] { Types.VARCHAR, Types.VARCHAR, Types.VARCHAR },
                        List.<Object[]> of(new Object[] { sessionSnapshot, active ? transactionTso : "0", "1" }));
            }
            if (sql.startsWith("SELECT TIDB_CURRENT_TSO")) {
                return rows(new String[] { "tso" }, new int[] { Types.VARCHAR },
                        List.<Object[]> of(new Object[] { active ? transactionTso : "0" }));
            }
            if (queryError != null) {
                throw queryError;
            }
            if (sql.startsWith("SHOW CREATE TABLE")) {
                return rows(new String[] { "create table" }, new int[] { Types.VARCHAR },
                        List.<Object[]> of(new Object[] { "CREATE TABLE fixture(c INT)" }));
            }
            if (sql.startsWith("SHOW FULL COLUMNS")) {
                return rows(new String[] { "field", "extra" }, new int[] { Types.VARCHAR, Types.VARCHAR },
                        List.<Object[]> of(new Object[] { "c", "" }));
            }
            if (mixedOnlyDisagreement && (!sql.contains("TIKV[") || !sql.contains("TIFLASH["))) {
                throw new SQLException("fixture baseline failure", "HY000", 1105);
            }
            if (sql.startsWith("EXPLAIN")) {
                List<Object[]> scans = new ArrayList<>();
                java.util.regex.Matcher flash = java.util.regex.Pattern.compile("TIFLASH\\[([^]]+)\\]").matcher(sql);
                String flashTables = flash.find() ? flash.group(1) : "";
                for (int i = 0; i < tableCount; i++) {
                    String table = "t" + i;
                    if (!sql.contains("`" + table + "`")) {
                        continue;
                    }
                    String engine = flashTables.contains("`" + table + "`") ? "tiflash" : "tikv";
                    scans.add(new Object[] { "TableFullScan_1", "cop[" + engine + "]", "table:" + table });
                }
                if (invalidPlan) {
                    scans.remove(1);
                }
                return rows(new String[] { "id", "task", "access object" },
                        new int[] { Types.VARCHAR, Types.VARCHAR, Types.VARCHAR }, scans);
            }
            if (driftOnResult) {
                sessionSnapshot = "0";
            }
            int value = (mixedOnlyDisagreement || preflightMismatch) && sql.contains("TIFLASH[`t0`]") ? 2 : 1;
            return rows(new String[] { "c" }, new int[] { resultType }, java.util.stream.IntStream.range(0, rowCount)
                    .mapToObj(i -> new Object[] { value }).collect(Collectors.toList()));
        }
    }
}
