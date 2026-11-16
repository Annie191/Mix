package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;

import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.StateToReproduce;
import sqlancer.tidb.TiDBOptions;
import sqlancer.tidb.TiDBProvider;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.ast.TiDBAggregate;
import sqlancer.tidb.ast.TiDBAggregate.TiDBAggregateFunction;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation.TiDBComparisonOperator;
import sqlancer.tidb.ast.TiDBColumnReference;
import sqlancer.tidb.ast.TiDBConstant;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

/** Opt-in checks of real TiDB metadata and probe SQL; does not require or claim TiFlash coverage. */
class MixedFactsIntegrationTest {
    @TempDir
    Path artifacts;

    @Test
    void zeroFillAndUnsignedIntegersProduceProtocolCompatibleExactSamples() throws Exception {
        try (Live fixture = new Live()) {
            // Fixture deliberately exercises deprecated formatting; keep production Warning rejection intact.
            try (Statement setup = fixture.connection.createStatement()) {
                setup.execute("CREATE TABLE t0(k SMALLINT(5) UNSIGNED ZEROFILL)");
                for (java.sql.SQLWarning warning = setup.getWarnings(); warning != null; warning = warning
                        .getNextWarning()) {
                    assertEquals(1681, warning.getErrorCode());
                }
            }
            fixture.io.exec("CREATE TABLE t1(k BIGINT UNSIGNED)");
            fixture.io.exec("INSERT INTO t0 VALUES(0),(12),(65535)");
            fixture.io.exec("INSERT INTO t1 VALUES(18446744073709551615)");
            fixture.state.updateSchema();
            Candidate query = MixedObservationTest.candidate(fixture.state.getSchema().getDatabaseTables(),
                    TiDBJoin.JoinType.LEFT, TiDBComparisonOperator.EQUALS, false);
            TiDBMixedObservations facts = new TiDBMixedObservations(query, "zerofill_q", 0, 0);
            facts.collect(fixture.io, List.of("sample_t0", "sample_t1"),
                    new TiDBMixedObservations.Budget(10, 64, 60_000));
            JsonObject metadata = TiDBMixedMetadata.collect(fixture.io, fixture.database, query.tables, 0).schema;
            JsonObject budget = JsonParser
                    .parseString("{\"model_calls\":1,\"probe_requests\":10,\"insert_rows\":6,\"time_ms\":5000}")
                    .getAsJsonObject();
            JsonObject packet = facts.statePacket("zerofill_req", fixture.database, metadata, new JsonArray(), budget);
            assertEquals(packet, TiDBDataPreparationProtocol.state(packet.toString()));
            java.util.Set<String> values = new java.util.HashSet<>();
            facts.samples.forEach(sample -> sample.getAsJsonObject().getAsJsonArray("rows").forEach(
                    row -> values.add(row.getAsJsonArray().get(0).getAsJsonObject().get("value").getAsString())));
            assertEquals(java.util.Set.of("0", "12", "65535", "18446744073709551615"), values);
            TiDBMixedSupport.save(artifacts.resolve("actual-zerofill-state.json"), packet);
        }
    }

    @Test
    void actualGeneratedColumnsDefaultsCompositeKeysPrefixKeysAndChecksArePreserved() throws Exception {
        try (Live fixture = new Live()) {
            String previous = fixture.io.textRows("SELECT @@global.tidb_enable_check_constraint AS enabled").get(0)
                    .get("enabled");
            try {
                fixture.io.exec("SET GLOBAL tidb_enable_check_constraint=ON");
                fixture.io.exec("CREATE TABLE t0(id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY, k INT NULL,"
                        + " n DECIMAL(38,2) NOT NULL DEFAULT 12.34, s VARCHAR(20) DEFAULT 'a,b',"
                        + " v INT AS (k+1) VIRTUAL, g INT AS (k+2) STORED, empty_s VARCHAR(5) DEFAULT '', UNIQUE KEY uk(n,k),"
                        + " UNIQUE KEY pref(s(3)), CONSTRAINT positive CHECK (k>=0),"
                        + " CONSTRAINT optional CHECK (k<100) NOT ENFORCED)");
                fixture.state.updateSchema();
                TiDBMixedMetadata metadata = TiDBMixedMetadata.collect(fixture.io, fixture.database,
                        fixture.state.getSchema().getDatabaseTables(), 4);
                assertTrue(metadata.schema.get("complete").getAsBoolean(), metadata.evidence.toString());
                JsonObject table = metadata.schema.getAsJsonArray("tables").get(0).getAsJsonObject();
                assertEquals("bigint unsigned",
                        table.getAsJsonArray("columns").get(0).getAsJsonObject().get("sql_type").getAsString());
                assertTrue(
                        table.getAsJsonArray("columns").get(0).getAsJsonObject().get("auto_increment").getAsBoolean());
                assertEquals("decimal(38,2)",
                        table.getAsJsonArray("columns").get(2).getAsJsonObject().get("sql_type").getAsString());
                assertEquals("12.34", table.getAsJsonArray("columns").get(2).getAsJsonObject().get("default_expression")
                        .getAsString());
                assertEquals("a,b", table.getAsJsonArray("columns").get(3).getAsJsonObject().get("default_expression")
                        .getAsString());
                assertTrue(table.getAsJsonArray("columns").get(4).getAsJsonObject().get("generated").getAsBoolean());
                assertTrue(table.getAsJsonArray("columns").get(5).getAsJsonObject().get("generated").getAsBoolean());
                assertEquals("", table.getAsJsonArray("columns").get(6).getAsJsonObject().get("default_expression")
                        .getAsString());
                Map<String, JsonObject> constraints = new java.util.HashMap<>();
                table.getAsJsonArray("constraints").forEach(
                        e -> constraints.put(e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject()));
                assertEquals("[\"n\",\"k\"]", constraints.get("uk").getAsJsonArray("columns").toString());
                assertEquals("3", constraints.get("pref").getAsJsonArray("key_parts").get(0).getAsJsonObject()
                        .get("prefix_length").getAsString());
                assertTrue(constraints.get("positive").get("enforced").getAsBoolean());
                assertFalse(constraints.get("optional").get("enforced").getAsBoolean());
                assertTrue(fixture.state.getSchema().getDatabaseTables().get(0).getColumns().get(4).isGenerated());
                TiDBMixedSupport.save(artifacts.resolve("actual-metadata.json"),
                        Map.of("schema", metadata.schema, "evidence", metadata.evidence));
            } finally {
                fixture.io.exec("SET GLOBAL tidb_enable_check_constraint=" + previous);
            }
        }
    }

    @Test
    void actualCountsSeparateOuterMatchesFilteredRowsAndGroupedOutput() throws Exception {
        try (Live fixture = new Live()) {
            fixture.io.exec("CREATE TABLE t0(k INT)");
            fixture.io.exec("CREATE TABLE t1(k INT)");
            fixture.io.exec("INSERT INTO t0 VALUES (1),(1),(NULL),(2)");
            fixture.io.exec("INSERT INTO t1 VALUES (1),(1),(3)");
            fixture.state.updateSchema();
            Candidate query = MixedObservationTest.candidate(fixture.state.getSchema().getDatabaseTables(),
                    TiDBJoin.JoinType.LEFT, TiDBComparisonOperator.EQUALS, false);
            TiDBMixedObservations facts = new TiDBMixedObservations(query, "real_q", 0, 0);
            facts.collect(fixture.io, facts.allIds(), new TiDBMixedObservations.Budget(100, 64, 60_000));
            assertEquals("4", MixedObservationTest.count(facts, "j0_pairs_before"));
            assertEquals("2", MixedObservationTest.count(facts, "j0_left_unmatched"));
            assertEquals("1", MixedObservationTest.count(facts, "j0_right_unmatched"));
            assertEquals("6", MixedObservationTest.count(facts, "query_input_before"));
            assertEquals("1", MixedObservationTest.count(facts, "j0_duplicate_after"));
            assertEquals("2", MixedObservationTest.count(facts, "j0_left_repeated"));
            assertEquals("2", MixedObservationTest.count(facts, "j0_left_unmatched_after"));
            assertTrue(
                    facts.observations.asList().stream()
                            .noneMatch(e -> "error".equals(e.getAsJsonObject().get("status").getAsString())),
                    facts.observations.toString());
            TiDBMixedMetadata metadata = TiDBMixedMetadata.collect(fixture.io, fixture.database, query.tables, 0);
            JsonObject budget = JsonParser
                    .parseString("{\"model_calls\":1,\"probe_requests\":10,\"insert_rows\":6,\"time_ms\":5000}")
                    .getAsJsonObject();
            JsonObject packet = facts.statePacket("real_req", fixture.database, metadata.schema, new JsonArray(),
                    budget);
            Path savedPacket = artifacts.resolve("actual-state-packet.json");
            TiDBMixedSupport.save(savedPacket, packet);
            assertEquals(packet, TiDBDataPreparationProtocol.state(java.nio.file.Files.readString(savedPacket)));
            query.ast.setGroupByExpressions(List.of(new TiDBColumnReference(query.tables.get(0).getColumns().get(0))));
            TiDBAggregate count = new TiDBAggregate(List.of(TiDBConstant.createIntConstant(1)),
                    TiDBAggregateFunction.COUNT);
            query.ast.setFetchColumns(List.of(count));
            query.ast.setHavingClause(new TiDBBinaryComparisonOperation(count, TiDBConstant.createIntConstant(1),
                    TiDBComparisonOperator.GREATER));
            query = new Candidate(query.ast, query.tables, List.of());
            TiDBMixedObservations grouped = new TiDBMixedObservations(query, "group_q", 0, 0);
            grouped.collect(fixture.io, grouped.allIds(), new TiDBMixedObservations.Budget(100, 64, 60_000));
            assertEquals("6", MixedObservationTest.count(grouped, "query_input_after"));
            JsonObject summary = MixedObservationTest.observation(grouped, "groups_after_having");
            assertEquals("exact", summary.get("status").getAsString(), summary.toString());
            assertEquals("1", summary.getAsJsonObject("value").getAsJsonArray("rows").get(0).getAsJsonArray().get(0)
                    .getAsJsonObject().get("value").getAsString());
            assertEquals("4", summary.getAsJsonObject("value").getAsJsonArray("rows").get(0).getAsJsonArray().get(1)
                    .getAsJsonObject().get("value").getAsString());
            fixture.io.textRows("SELECT @@tidb_snapshot AS restored")
                    .forEach(row -> assertEquals("", row.get("restored")));
            TiDBMixedSupport.save(artifacts.resolve("actual-observations.json"),
                    Map.of("sql", query.sql, "observations", grouped.observations, "samples", grouped.samples, "audit",
                            grouped.audit, "firstRound", facts.observations));
            query.ast.setGroupByExpressions(List.of());
            query.ast.setHavingClause(null);
            query.ast.setWhereClause(new TiDBBinaryComparisonOperation(TiDBConstant.createIntConstant(1),
                    TiDBConstant.createIntConstant(0), TiDBComparisonOperator.EQUALS));
            TiDBMixedObservations global = new TiDBMixedObservations(new Candidate(query.ast, query.tables, List.of()),
                    "global_q", 0, 0);
            global.collect(fixture.io, List.of("query_input_after", "query_empty", "groups_before_having"),
                    new TiDBMixedObservations.Budget(10, 10, 60_000));
            assertEquals("0", MixedObservationTest.count(global, "query_input_after"));
            assertFalse(MixedObservationTest.observation(global, "query_empty").getAsJsonObject("value").get("empty")
                    .getAsBoolean());
            assertEquals("unsupported",
                    MixedObservationTest.observation(global, "groups_before_having").get("status").getAsString());
        }
    }

    private static final class Live implements AutoCloseable {
        final String database = "mix_facts_" + UUID.randomUUID().toString().replace("-", "");
        final Connection connection;
        final TiDBGlobalState state = new TiDBGlobalState();
        final TiDBMixedSupport io;

        Live() throws SQLException {
            assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("MS_FACTS_INTEGRATION", "false")));
            connection = DriverManager.getConnection(
                    "jdbc:mysql://" + System.getenv().getOrDefault("TIDB_HOST", "127.0.0.1") + ":"
                            + System.getenv().getOrDefault("TIDB_PORT", "4000") + "/",
                    System.getenv().getOrDefault("TIDB_USER", "root"),
                    System.getenv().getOrDefault("TIDB_PASSWORD", ""));
            state.setConnection(new SQLConnection(connection));
            state.setDatabaseName(database);
            state.setMainOptions(new MainOptions());
            state.setDbmsSpecificOptions(new TiDBOptions());
            state.setRandomly(new Randomly(42));
            state.setState(new StateToReproduce(database, new TiDBProvider()));
            io = new TiDBMixedSupport(state);
            TiDBMixedTarget.validate(io.textRows("SELECT TIDB_VERSION() AS version").get(0).get("version"));
            io.exec("CREATE DATABASE " + TiDBMixedSupport.quoteIdentifier(database));
            io.exec("USE " + TiDBMixedSupport.quoteIdentifier(database));
            io.exec("SET SESSION tidb_isolation_read_engines='tikv'");
        }

        @Override
        public void close() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET SESSION tidb_snapshot=''");
                statement.execute("DROP DATABASE " + TiDBMixedSupport.quoteIdentifier(database));
            } finally {
                connection.close();
            }
        }
    }
}
