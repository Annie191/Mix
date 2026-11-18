package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.StateToReproduce;
import sqlancer.tidb.TiDBOptions;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.TiDBProvider;

/** Opt-in test against a real cluster, with a separate disposable database. */
class MixedIntegrationTest {
    @TempDir
    Path artifacts;

    @Test
    void realFourPathsPreserveDuplicatesAndReplayTheMaterializedCase() throws Exception {
        assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("MS_INTEGRATION", "false")));
        String host = System.getenv().getOrDefault("TIDB_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("TIDB_PORT", "4000");
        String user = System.getenv().getOrDefault("TIDB_USER", "root");
        String password = System.getenv().getOrDefault("TIDB_PASSWORD", "");
        String database = "mix_integration_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/", user,
                password)) {
            TiDBGlobalState state = new TiDBGlobalState();
            state.setConnection(new SQLConnection(connection));
            state.setDatabaseName(database);
            state.setMainOptions(new MainOptions());
            state.setDbmsSpecificOptions(new TiDBOptions());
            state.setRandomly(new Randomly(42));
            state.setState(new StateToReproduce(database, new TiDBProvider()));
            TiDBMixedSupport io = new TiDBMixedSupport(state);
            io.initialize();
            io.exec("CREATE DATABASE " + TiDBMixedSupport.quoteIdentifier(database));
            io.exec("USE " + TiDBMixedSupport.quoteIdentifier(database));
            try {
                io.exec("CREATE TABLE t0(k INT, v DECIMAL(38,2), b VARBINARY(8))");
                io.exec("CREATE TABLE t1(k INT, v DECIMAL(38,2))");
                io.exec("INSERT INTO t0 VALUES (1,123456789012345678901234567890.01,X'00FF'),"
                        + "(1,123456789012345678901234567890.01,X'00FF'),(NULL,3,NULL),(2,2,X'00')");
                io.exec("INSERT INTO t1 VALUES (1,4),(1,4),(3,5)");
                state.updateSchema();
                List<String> restore = io.dump(TiDBMixedGeneration.baseTables(state));
                io.replicas(TiDBMixedGeneration.baseTables(state));
                io.beginSnapshot();
                String snapshot = io.snapshot;
                assertTrue(TiDBMixedSupport.positiveTso(snapshot));
                io.verifySnapshot(snapshot);
                // Commit a new matching key after freezing: all historical reads must still see the old data.
                try (Connection writer = DriverManager
                        .getConnection("jdbc:mysql://" + host + ":" + port + "/" + database, user, password);
                        Statement statement = writer.createStatement()) {
                    statement.execute("INSERT INTO t0 VALUES (99,99,X'01')");
                    statement.execute("INSERT INTO t1 VALUES (99,99)");
                    try (java.sql.ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM t0")) {
                        assertTrue(rows.next());
                        assertEquals(5, rows.getInt(1));
                    }
                }
                io.preflight(TiDBMixedGeneration.baseTables(state));
                String query = "SELECT t0.k,t0.v,t0.b,t1.v FROM t0 LEFT JOIN t1 ON t0.k=t1.k";
                List<TiDBMixedSupport.Run> runs = io.executeAll(query,
                        TiDBMixedSupport.configurations(List.of("t0", "t1")));
                assertEquals("PASS", TiDBMixedSupport.compare(runs).status, new com.google.gson.Gson().toJson(runs));
                assertTrue(runs.stream().allMatch(r -> r.rows.size() == 6 && r.bag.size() == 3));
                assertTrue(runs.stream().allMatch(r -> r.snapshot.equals(io.snapshot)));
                for (Map<String, Object> event : io.events) {
                    if ("PREFLIGHT".equals(event.get("stage"))) {
                        TiDBMixedSupport.Run kv = (TiDBMixedSupport.Run) event.get("tikv");
                        TiDBMixedSupport.Run flash = (TiDBMixedSupport.Run) event.get("tiflash");
                        assertEquals(snapshot, kv.snapshot);
                        assertEquals(snapshot, flash.snapshot);
                        assertEquals(kv.config.engines.containsKey("t0") ? 4 : 3, kv.rows.size());
                    }
                }
                Path evidence = Path.of("target", "mixed-snapshot-live");
                java.nio.file.Files.createDirectories(evidence);
                TiDBMixedSupport.save(evidence.resolve("historical-read.json"),
                        Map.of("snapshot", snapshot, "environment", io.environment, "events", io.events, "runs", runs,
                                "outcome", TiDBMixedSupport.compare(runs), "currentT0Rows", 5, "snapshotT0Rows", 4));
                Path saved = artifacts.resolve("case.json");
                TiDBMixedSupport.save(saved,
                        Map.of("target", TiDBMixedTarget.VERSION, "tidbCommit", TiDBMixedTarget.COMMIT, "attempts",
                                List.of(Map.of("sql", query, "runs", runs, "restoreStatements", restore))));
                TiDBMixedReplay.main(new String[] { saved.toString(), "0" });
                // Replay intentionally retains its database. Clean up the test-created replay database here.
                try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(artifacts)) {
                    for (Path replay : files.filter(p -> p.getFileName().toString().startsWith("replay_"))
                            .collect(java.util.stream.Collectors.toList())) {
                        com.google.gson.JsonObject replayReport = com.google.gson.JsonParser
                                .parseString(java.nio.file.Files.readString(replay)).getAsJsonObject();
                        assertEquals("PASS", replayReport.getAsJsonObject("outcome").get("status").getAsString());
                        assertTrue(TiDBMixedSupport.positiveTso(
                                replayReport.getAsJsonObject("environment").get("snapshotTSO").getAsString()));
                        java.nio.file.Files.copy(replay, evidence.resolve("replay.json"),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        String replayDatabase = replayReport.get("database").getAsString();
                        io.reset();
                        io.exec("DROP DATABASE " + TiDBMixedSupport.quoteIdentifier(replayDatabase));
                    }
                }
            } finally {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET SESSION tidb_snapshot=''");
                    statement.execute("DROP DATABASE " + TiDBMixedSupport.quoteIdentifier(database));
                }
            }
        }
    }
}
