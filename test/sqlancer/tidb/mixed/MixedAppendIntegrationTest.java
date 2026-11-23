package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.StateToReproduce;
import sqlancer.tidb.TiDBOptions;
import sqlancer.tidb.TiDBProvider;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;

/** Handwritten JSON plans on the target TiDB revision; no model calls and no TiFlash claims. */
class MixedAppendIntegrationTest {
    @TempDir
    Path artifacts;

    @Test
    void tinyintOneUsesNumericCatalogSemanticsAndRealBitRetainsBinaryValues() throws Exception {
        try (Live fixture = new Live()) {
            fixture.io.exec("ALTER TABLE t1 ADD t TINYINT(1), ADD u TINYINT(1) UNSIGNED");
            fixture.state.updateSchema();
            fixture.packet.add("schema", TiDBMixedMetadata.collect(fixture.io, fixture.database,
                    fixture.state.getSchema().getDatabaseTables(), 0).schema);
            JsonObject plan = MixedAppendTest.plan(fixture.packet,
                    "[{\"table\":\"t1\",\"rows\":[{\"k\":{\"type\":\"integer\",\"value\":\"12\"},"
                            + "\"t\":{\"type\":\"integer\",\"value\":\"2\"},\"u\":{\"type\":\"integer\",\"value\":\"255\"}}]}]");
            JsonObject feedback = fixture.executor(1).execute(plan.toString(), fixture.packet);
            JsonObject write = MixedAppendTest.write(feedback, 0);
            assertEquals("success", write.get("status").getAsString(), feedback.toString());
            JsonObject actual = write.getAsJsonObject("actual_values");
            assertEquals(MixedAppendTest.json("{\"type\":\"integer\",\"value\":\"2\"}"), actual.get("t"));
            assertEquals(MixedAppendTest.json("{\"type\":\"integer\",\"value\":\"255\"}"), actual.get("u"));
            TiDBMixedReadOnly.Result sample = TiDBMixedReadOnly.read(fixture.io, "SELECT t AS aliased, u FROM t1", 10,
                    5);
            assertEquals(actual.get("t"), sample.rows.get(0).getAsJsonArray().get(0));
            fixture.io.exec("CREATE TABLE bit_probe(b BIT(3))");
            fixture.io.exec("INSERT INTO bit_probe VALUES (b'101')");
            TiDBMixedReadOnly.Result bits = TiDBMixedReadOnly.read(fixture.io, "SELECT b FROM bit_probe", 10, 5);
            assertEquals(MixedAppendTest.json("{\"type\":\"binary\",\"value\":\"BQ==\"}"),
                    bits.rows.get(0).getAsJsonArray().get(0));
        }
    }

    @Test
    void unsignedMaxDecimalBinaryTextAndGeneratedValuesAreActuallyCommitted() throws Exception {
        try (Live fixture = new Live()) {
            JsonObject row = MixedAppendTest.json(MixedAppendTest.row(2));
            row.getAsJsonObject("k").addProperty("value", "18446744073709551615");
            row.getAsJsonObject("n").addProperty("value", "123456789012345678901234567890.01");
            row.getAsJsonObject("s").addProperty("value", "'); UPDATE t0 SET s='changed'; --\\中文");
            JsonObject plan = MixedAppendTest.plan(fixture.packet, MixedAppendTest.operations(row.toString()));
            JsonObject feedback = fixture.executor(1).execute(plan.toString(), fixture.packet);
            JsonObject write = MixedAppendTest.write(feedback, 0);
            assertEquals("success", write.get("status").getAsString(), feedback.toString());
            JsonObject actual = write.getAsJsonObject("actual_values");
            assertEquals("18446744073709551615", actual.getAsJsonObject("k").get("value").getAsString());
            assertEquals("123456789012345678901234567890.01", actual.getAsJsonObject("n").get("value").getAsString());
            assertEquals("AP8=", actual.getAsJsonObject("b").get("value").getAsString());
            assertEquals(row.get("s"), actual.get("s"));
            assertEquals("18446744073709551616", actual.getAsJsonObject("g").get("value").getAsString());
            assertTrue(actual.has("id"));
            assertFalse(write.getAsJsonObject("attempted_values").has("id"));
            assertFalse(write.getAsJsonObject("attempted_values").has("g"));
            assertEquals("original", fixture.scalar("SELECT s AS v FROM t0 WHERE k=1"));
            TiDBMixedSupport.save(artifacts.resolve("manual-append-precision.json"),
                    Map.of("state", fixture.packet, "plan", plan, "feedback", feedback));
        }
    }

    @Test
    void duplicateFailureDoesNotUpdateRowsAndOtherRowsCanCommit() throws Exception {
        try (Live fixture = new Live()) {
            JsonObject plan = MixedAppendTest.plan(fixture.packet,
                    MixedAppendTest.operations(MixedAppendTest.row(2), MixedAppendTest.row(1), MixedAppendTest.row(3)));
            TiDBMixedAppendExecutor executor = fixture.executor(3);
            JsonObject feedback = executor.execute(plan.toString(), fixture.packet);
            assertEquals("success", MixedAppendTest.write(feedback, 0).get("status").getAsString(),
                    feedback.toString());
            assertEquals("failed", MixedAppendTest.write(feedback, 1).get("status").getAsString(), feedback.toString());
            assertEquals(1062, MixedAppendTest.write(feedback, 1).get("error_code").getAsInt());
            assertEquals("success", MixedAppendTest.write(feedback, 2).get("status").getAsString(),
                    feedback.toString());
            assertEquals(2, executor.dataRevision());
            assertEquals(0, executor.remainingRows());
            assertEquals("3", fixture.scalar("SELECT COUNT(*) AS v FROM t0"));
            assertEquals("original", fixture.scalar("SELECT s AS v FROM t0 WHERE k=1"));
            TiDBMixedSupport.save(artifacts.resolve("manual-append-partial.json"),
                    Map.of("state", fixture.packet, "plan", plan, "feedback", feedback));
        }
    }

    @Test
    void generatedColumnsOversizedPlansAndExistingTransactionAreRejected() throws Exception {
        try (Live fixture = new Live()) {
            TiDBMixedAppendExecutor executor = fixture.executor(1);
            JsonObject row = MixedAppendTest.json(MixedAppendTest.row(2));
            row.add("g", MixedAppendTest.json("{\"type\":\"decimal\",\"value\":\"3\"}"));
            JsonObject generated = MixedAppendTest.plan(fixture.packet, MixedAppendTest.operations(row.toString()));
            assertThrows(IllegalArgumentException.class, () -> executor.execute(generated.toString(), fixture.packet));
            JsonObject oversized = MixedAppendTest.plan(fixture.packet,
                    MixedAppendTest.operations(MixedAppendTest.row(2), MixedAppendTest.row(3)));
            assertThrows(IllegalArgumentException.class, () -> executor.execute(oversized.toString(), fixture.packet));
            JsonObject plan = MixedAppendTest.plan(fixture.packet, MixedAppendTest.operations(MixedAppendTest.row(2)));
            fixture.io.exec("BEGIN");
            fixture.io.exec("INSERT INTO t1(k) VALUES(999)");
            assertThrows(IllegalArgumentException.class, () -> executor.execute(plan.toString(), fixture.packet));
            assertEquals("1", fixture.scalar("SELECT COUNT(*) AS v FROM t1"));
            fixture.io.exec("ROLLBACK");
            assertEquals("0", fixture.scalar("SELECT COUNT(*) AS v FROM t1"));
            assertEquals("1", fixture.scalar("SELECT COUNT(*) AS v FROM t0"));
        }
    }

    @Test
    void decimalCoercionAndOutOfRangeInputDoNotCommitDifferentValues() throws Exception {
        try (Live fixture = new Live()) {
            JsonObject rounded = MixedAppendTest.json(MixedAppendTest.row(2));
            rounded.getAsJsonObject("n").addProperty("value", "12.345");
            JsonObject outside = MixedAppendTest.json(MixedAppendTest.row(3));
            outside.getAsJsonObject("k").addProperty("value", "18446744073709551616");
            JsonObject plan = MixedAppendTest.plan(fixture.packet,
                    MixedAppendTest.operations(rounded.toString(), outside.toString()));
            JsonObject feedback = fixture.executor(2).execute(plan.toString(), fixture.packet);
            assertEquals("failed", MixedAppendTest.write(feedback, 0).get("status").getAsString(), feedback.toString());
            assertEquals("failed", MixedAppendTest.write(feedback, 1).get("status").getAsString(), feedback.toString());
            assertEquals(0, feedback.get("data_revision_after").getAsInt());
            assertEquals("1", fixture.scalar("SELECT COUNT(*) AS v FROM t0"));
        }
    }

    @Test
    void actualCheckConstraintRejectsOneRowAndRetainsOtherSuccessfulRows() throws Exception {
        try (Live fixture = new Live()) {
            String previous = fixture.scalar("SELECT @@global.tidb_enable_check_constraint AS v");
            try {
                fixture.io.exec("SET GLOBAL tidb_enable_check_constraint=ON");
                fixture.io.exec("ALTER TABLE t0 ADD CONSTRAINT positive_n CHECK(n>0)");
                fixture.state.updateSchema();
                fixture.packet.add("schema", TiDBMixedMetadata.collect(fixture.io, fixture.database,
                        fixture.state.getSchema().getDatabaseTables(), 0).schema);
                JsonObject invalid = MixedAppendTest.json(MixedAppendTest.row(2));
                invalid.getAsJsonObject("n").addProperty("value", "-1");
                JsonObject plan = MixedAppendTest.plan(fixture.packet,
                        MixedAppendTest.operations(invalid.toString(), MixedAppendTest.row(3)));
                JsonObject feedback = fixture.executor(2).execute(plan.toString(), fixture.packet);
                assertEquals("failed", MixedAppendTest.write(feedback, 0).get("status").getAsString(),
                        feedback.toString());
                assertEquals("success", MixedAppendTest.write(feedback, 1).get("status").getAsString(),
                        feedback.toString());
                assertEquals("2", fixture.scalar("SELECT COUNT(*) AS v FROM t0"));
                assertEquals(1, feedback.get("data_revision_after").getAsLong());
            } finally {
                fixture.io.exec("SET GLOBAL tidb_enable_check_constraint=" + previous);
            }
        }
    }

    private static final class Live implements AutoCloseable {
        final String database = "mix_append_" + UUID.randomUUID().toString().replace("-", "");
        final Connection connection;
        final TiDBGlobalState state = new TiDBGlobalState();
        final TiDBMixedSupport io;
        final JsonObject packet;

        Live() throws Exception {
            assumeTrue(Boolean.parseBoolean(System.getenv().getOrDefault("MS_APPEND_INTEGRATION", "false")));
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
            TiDBMixedTarget.validate(io.textRows("SELECT TIDB_VERSION() AS v").get(0).get("v"));
            io.exec("CREATE DATABASE " + TiDBMixedSupport.quoteIdentifier(database));
            io.exec("USE " + TiDBMixedSupport.quoteIdentifier(database));
            io.exec("SET NAMES utf8mb4");
            io.exec("CREATE TABLE t0(id BIGINT AUTO_INCREMENT PRIMARY KEY,k BIGINT UNSIGNED NOT NULL UNIQUE,"
                    + "n DECIMAL(38,2),s VARCHAR(1000),b VARBINARY(8),g DECIMAL(21,0) AS (CAST(k AS DECIMAL(21,0))+1) STORED)");
            io.exec("CREATE TABLE t1(k INT)");
            io.exec("INSERT INTO t0(k,n,s) VALUES (1,1.23,'original')");
            state.updateSchema();
            packet = MixedAppendTest.json(Files.readString(Path.of("docs/data-preparation-v1/01-state.json")));
            packet.addProperty("case_id", database);
            packet.addProperty("request_id", "manual_1");
            packet.add("schema",
                    TiDBMixedMetadata.collect(io, database, state.getSchema().getDatabaseTables(), 0).schema);
            packet.add("samples", new JsonArray());
        }

        TiDBMixedAppendExecutor executor(int rows) {
            packet.getAsJsonObject("remaining_budget").addProperty("insert_rows", rows);
            return new TiDBMixedAppendExecutor(state, packet, 100);
        }

        String scalar(String sql) throws SQLException {
            return io.textRows(sql).get(0).get("v");
        }

        @Override
        public void close() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ROLLBACK");
                statement.execute("DROP DATABASE " + TiDBMixedSupport.quoteIdentifier(database));
            } finally {
                connection.close();
            }
        }
    }
}
