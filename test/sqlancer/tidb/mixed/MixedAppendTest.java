package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.SQLConnection;
import sqlancer.StateToReproduce;
import sqlancer.tidb.TiDBProvider;
import sqlancer.tidb.TiDBSchema;
import sqlancer.tidb.TiDBSchema.TiDBColumn;
import sqlancer.tidb.TiDBSchema.TiDBCompositeDataType;
import sqlancer.tidb.TiDBSchema.TiDBDataType;
import sqlancer.tidb.TiDBSchema.TiDBTable;
import sqlancer.tidb.gen.TiDBInsertGenerator;

/** Actual prepared INSERT and transaction execution in H2; only TiDB session/catalog reads are fixtures. */
class MixedAppendTest {
    static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    static JsonObject plan(JsonObject packet, String rows) throws Exception {
        JsonObject plan = json(Files.readString(Path.of("docs/data-preparation-v1/04-append-rows.json")));
        for (String key : List.of("request_id", "case_id", "query_id", "schema_revision", "data_revision")) {
            plan.add(key, packet.get(key));
        }
        plan.add("evidence_ids", new JsonArray());
        plan.add("verification_requests", new JsonArray());
        plan.addProperty("reason", "Handwritten plan for controlled append execution tests");
        plan.addProperty("goal", "Check individual row outcomes and exact typed values");
        plan.addProperty("expected_effect", "Only successful rows are appended; inspect actual feedback");
        plan.addProperty("verification_limit", "Manual executor test; no automatic scenario verification requested");
        plan.add("operations", JsonParser.parseString(rows));
        return plan;
    }

    static String row(long key) {
        return "{\"k\":{\"type\":\"integer\",\"value\":\"" + key + "\"},"
                + "\"n\":{\"type\":\"decimal\",\"value\":\"12.34\"}," + "\"s\":{\"type\":\"text\",\"value\":\"kept\"},"
                + "\"b\":{\"type\":\"binary\",\"value\":\"AP8=\"}}";
    }

    static String operations(String... rows) {
        return "[{\"table\":\"t0\",\"rows\":[" + String.join(",", rows) + "]}]";
    }

    static JsonObject write(JsonObject feedback, int index) {
        return feedback.getAsJsonArray("writes").get(index).getAsJsonObject();
    }

    @Test
    void ordinaryGeneratorNeverAddsReplacementIgnoreOrUpdateAndExcludesGeneratedColumns() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (int i = 0; i < 200; i++) {
                TiDBInsertGenerator.AppendRow row = TiDBInsertGenerator.appendRow(fixture.state, fixture.tables.get(0),
                        Map.of("k", 12));
                assertEquals("INSERT INTO `t0` (`k`,`n`,`s`,`b`) VALUES (?,?,?,?)", row.sql);
                assertEquals(4, row.values.size());
                assertEquals(12, row.values.get(0));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> TiDBInsertGenerator.appendRow(fixture.state, fixture.tables.get(0), Map.of("g", 12)));
        }
    }

    @Test
    void duplicateKeyFailsWithoutUpdatingExistingRowsAndPartialSuccessIsRetained() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JsonObject plan = plan(fixture.packet, operations(row(2), row(1), row(3)));
            JsonObject feedback = fixture.executor(3).execute(plan.toString(), fixture.packet);
            assertEquals("success", write(feedback, 0).get("status").getAsString());
            assertEquals("failed", write(feedback, 1).get("status").getAsString());
            assertEquals("success", write(feedback, 2).get("status").getAsString());
            assertEquals(2, feedback.get("data_revision_after").getAsLong());
            assertEquals(0, feedback.get("remaining_insert_rows").getAsLong());
            assertEquals(3, feedback.get("attempted_rows").getAsInt());
            assertEquals("original", fixture.scalar("SELECT s FROM t0 WHERE k=1"));
            assertEquals("3", fixture.scalar("SELECT COUNT(*) FROM t0"));
            assertEquals("3", write(feedback, 0).getAsJsonObject("actual_values").getAsJsonObject("g").get("value")
                    .getAsString());
            assertFalse(fixture.active);
            assertEquals("tikv,tiflash", fixture.engines);
        }
    }

    @Test
    void onePlanAppendsAcrossTablesAndPreservesDuplicateRowsWithoutAPrimaryKey() throws Exception {
        try (Fixture fixture = new Fixture()) {
            try (Statement statement = fixture.database.createStatement()) {
                statement.execute("INSERT INTO t1(k) VALUES(12),(12)");
            }
            JsonObject plan = plan(fixture.packet, "[{\"table\":\"t0\",\"rows\":[" + row(2) + "]},"
                    + "{\"table\":\"t1\",\"rows\":[{\"k\":{\"type\":\"integer\",\"value\":\"12\"}}]}]");
            JsonObject feedback = fixture.executor(2).execute(plan.toString(), fixture.packet);
            assertEquals("success", write(feedback, 0).get("status").getAsString(), feedback.toString());
            assertEquals("success", write(feedback, 1).get("status").getAsString(), feedback.toString());
            assertEquals("t1", write(feedback, 1).get("table").getAsString());
            assertEquals("3", fixture.scalar("SELECT COUNT(*) FROM t1 WHERE k=12"));
            assertEquals(2, feedback.get("data_revision_after").getAsLong());
        }
    }

    @Test
    void exactLargeIntegersDecimalsBinaryAndSqlLookingTextAreWrittenAsData() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JsonObject values = json(row(9223372036854775806L));
            values.getAsJsonObject("n").addProperty("value", "123456789012345678901234567890.01");
            values.getAsJsonObject("s").addProperty("value", "'); UPDATE t0 SET s='bad'; --\\\n中文");
            JsonObject feedback = fixture.executor(1)
                    .execute(plan(fixture.packet, operations(values.toString())).toString(), fixture.packet);
            assertEquals("success", write(feedback, 0).get("status").getAsString(), feedback.toString());
            JsonObject actual = write(feedback, 0).getAsJsonObject("actual_values");
            assertEquals("9223372036854775806", actual.getAsJsonObject("k").get("value").getAsString());
            assertEquals("123456789012345678901234567890.01", actual.getAsJsonObject("n").get("value").getAsString());
            assertEquals("AP8=", actual.getAsJsonObject("b").get("value").getAsString());
            assertEquals(values.get("s"), actual.get("s"));
            assertEquals("original", fixture.scalar("SELECT s FROM t0 WHERE k=1"));
        }
    }

    @Test
    void omittedWritableValuesComeFromSqlancerAndActualGeneratedValuesAreReported() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JsonObject values = json(row(2));
            values.remove("n");
            JsonObject feedback = fixture.executor(1)
                    .execute(plan(fixture.packet, operations(values.toString())).toString(), fixture.packet);
            assertEquals("success", write(feedback, 0).get("status").getAsString(), feedback.toString());
            assertTrue(write(feedback, 0).getAsJsonObject("attempted_values").has("n"));
            assertFalse(write(feedback, 0).getAsJsonObject("attempted_values").has("g"));
            assertTrue(write(feedback, 0).getAsJsonObject("actual_values").has("g"));
        }
    }

    @Test
    void explicitNullEmptyTextAndEmptyBinaryAreKeptDistinct() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JsonObject values = json(row(2));
            values.add("n", json("{\"type\":\"null\",\"value\":null}"));
            values.getAsJsonObject("s").addProperty("value", "");
            values.getAsJsonObject("b").addProperty("value", "");
            JsonObject feedback = fixture.executor(1)
                    .execute(plan(fixture.packet, operations(values.toString())).toString(), fixture.packet);
            assertEquals("success", write(feedback, 0).get("status").getAsString(), feedback.toString());
            JsonObject actual = write(feedback, 0).getAsJsonObject("actual_values");
            assertEquals(values.get("n"), actual.get("n"));
            assertEquals(values.get("s"), actual.get("s"));
            assertEquals(values.get("b"), actual.get("b"));
        }
    }

    @Test
    void generatedAssignmentsAreRejectedBeforeAnyDataMutation() throws Exception {
        try (Fixture fixture = new Fixture()) {
            JsonObject values = json(row(2));
            values.add("g", json("{\"type\":\"integer\",\"value\":\"99\"}"));
            TiDBMixedAppendExecutor executor = fixture.executor(1);
            JsonObject plan = plan(fixture.packet, operations(values.toString()));
            assertThrows(IllegalArgumentException.class, () -> executor.execute(plan.toString(), fixture.packet));
            assertEquals(1, executor.remainingRows());
            assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t0"));
        }
    }

    @Test
    void inflatedOrForgedGeneratedMetadataCannotAuthorizeAWritableGeneratedColumn() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.packet.getAsJsonObject("schema").getAsJsonArray("tables").get(0).getAsJsonObject()
                    .getAsJsonArray("columns").get(4).getAsJsonObject().addProperty("generated", false);
            TiDBMixedAppendExecutor executor = fixture.executor(1);
            JsonObject plan = plan(fixture.packet, operations(row(2)));
            assertThrows(IllegalArgumentException.class, () -> executor.execute(plan.toString(), fixture.packet));
            assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t0"));
        }
    }

    @Test
    void oversizedPlansAreRejectedBeforeTheFirstRow() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TiDBMixedAppendExecutor executor = fixture.executor(1);
            JsonObject plan = plan(fixture.packet, operations(row(2), row(3)));
            assertThrows(IllegalArgumentException.class, () -> executor.execute(plan.toString(), fixture.packet));
            assertEquals(1, executor.remainingRows());
            assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t0"));
        }
    }

    @Test
    void failedSessionSetupReportsAllRowsAsNotAttemptedWithoutSpendingRowBudget() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.failSetup = true;
            JsonObject feedback = fixture.executor(2)
                    .execute(plan(fixture.packet, operations(row(2), row(3))).toString(), fixture.packet);
            assertEquals("not_attempted", write(feedback, 0).get("status").getAsString());
            assertEquals("not_attempted", write(feedback, 1).get("status").getAsString());
            assertEquals(0, feedback.get("attempted_rows").getAsInt());
            assertEquals(2, feedback.get("remaining_insert_rows").getAsInt());
            assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t0"));
            assertFalse(feedback.get("requires_rebuild").getAsBoolean());
        }
    }

    @Test
    void failedAttemptsSpendThePersistentBudgetEvenIfTheNextPacketClaimsMore() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TiDBMixedAppendExecutor executor = fixture.executor(2);
            executor.execute(plan(fixture.packet, operations(row(1))).toString(), fixture.packet);
            fixture.packet.addProperty("request_id", "next");
            fixture.packet.getAsJsonObject("remaining_budget").addProperty("insert_rows", 100);
            executor.execute(plan(fixture.packet, operations(row(1))).toString(), fixture.packet);
            fixture.packet.addProperty("request_id", "over_budget");
            JsonObject next = plan(fixture.packet, operations(row(2)));
            assertThrows(IllegalArgumentException.class, () -> executor.execute(next.toString(), fixture.packet));
            assertEquals(0, executor.remainingRows());
            assertEquals(0, executor.dataRevision());
        }
    }

    @Test
    void staleVersionsAndRepeatedRequestIdsCannotReplayWrites() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TiDBMixedAppendExecutor executor = fixture.executor(3);
            JsonObject plan = plan(fixture.packet, operations(row(2)));
            executor.execute(plan.toString(), fixture.packet);
            assertThrows(IllegalArgumentException.class, () -> executor.execute(plan.toString(), fixture.packet));
            fixture.packet.addProperty("data_revision", 1);
            JsonObject replay = plan(fixture.packet, operations(row(3)));
            assertThrows(IllegalArgumentException.class, () -> executor.execute(replay.toString(), fixture.packet));
            assertEquals("2", fixture.scalar("SELECT COUNT(*) FROM t0"));
        }
    }

    @Test
    void frozenOrDiscrepantDataCannotBeAppended() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TiDBMixedAppendExecutor executor = fixture.executor(1);
            JsonObject plan = plan(fixture.packet, operations(row(2)));
            fixture.packet.addProperty("discrepancy_found", true);
            assertThrows(IllegalArgumentException.class, () -> executor.execute(plan.toString(), fixture.packet));
            fixture.packet.addProperty("discrepancy_found", false);
            executor.freeze();
            assertThrows(IllegalArgumentException.class, () -> executor.execute(plan.toString(), fixture.packet));
        }
    }

    @Test
    void warningsAndUnannouncedDecimalRoundingRollBackTheRow() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.insertWarning = true;
            TiDBMixedAppendExecutor executor = fixture.executor(2);
            JsonObject feedback = executor.execute(plan(fixture.packet, operations(row(2))).toString(), fixture.packet);
            assertEquals("failed", write(feedback, 0).get("status").getAsString());
            assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t0"));
            fixture.insertWarning = false;
            fixture.packet.addProperty("request_id", "rounding");
            JsonObject values = json(row(3));
            values.getAsJsonObject("n").addProperty("value", "12.345");
            feedback = executor.execute(plan(fixture.packet, operations(values.toString())).toString(), fixture.packet);
            assertEquals("failed", write(feedback, 0).get("status").getAsString());
            assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t0"));
            assertEquals(0, executor.dataRevision());
        }
    }

    @Test
    void unexpectedChangesToExistingRowsAreDetectedAndRolledBack() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.injectUpdate = true;
            JsonObject feedback = fixture.executor(1).execute(plan(fixture.packet, operations(row(2))).toString(),
                    fixture.packet);
            assertEquals("failed", write(feedback, 0).get("status").getAsString());
            assertEquals("original", fixture.scalar("SELECT s FROM t0 WHERE k=1"));
            assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t0"));
        }
    }

    @Test
    void incompleteTableReadsPreventCommitRatherThanClaimExactChanges() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.executor(1);
            TiDBMixedAppendExecutor executor = new TiDBMixedAppendExecutor(fixture.state, fixture.packet, 1);
            JsonObject feedback = executor.execute(plan(fixture.packet, operations(row(2))).toString(), fixture.packet);
            assertEquals("failed", write(feedback, 0).get("status").getAsString());
            assertEquals("1", fixture.scalar("SELECT COUNT(*) FROM t0"));
        }
    }

    @Test
    void anExistingTransactionOrSnapshotIsNotCommittedOrCleared() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TiDBMixedAppendExecutor executor = fixture.executor(1);
            JsonObject plan = plan(fixture.packet, operations(row(2)));
            fixture.active = true;
            assertThrows(IllegalArgumentException.class, () -> executor.execute(plan.toString(), fixture.packet));
            assertTrue(fixture.active);
            fixture.active = false;
            fixture.snapshot = "1234";
            assertThrows(IllegalArgumentException.class, () -> executor.execute(plan.toString(), fixture.packet));
            assertEquals("1234", fixture.snapshot);
        }
    }

    @Test
    void unknownCommitOutcomeStopsLaterRowsAndCannotBeReportedAsNoChange() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.loseCommitReply = true;
            TiDBMixedAppendExecutor executor = fixture.executor(2);
            JsonObject feedback = executor.execute(plan(fixture.packet, operations(row(2), row(3))).toString(),
                    fixture.packet);
            assertEquals("unknown", write(feedback, 0).get("status").getAsString());
            assertEquals("not_attempted", write(feedback, 1).get("status").getAsString());
            assertTrue(feedback.get("requires_rebuild").getAsBoolean());
            assertTrue(feedback.get("data_revision_after").isJsonNull());
            assertTrue(write(feedback, 0).get("affected_rows").isJsonNull());
            assertEquals("2", fixture.scalar("SELECT COUNT(*) FROM t0"));
            assertThrows(IllegalStateException.class, executor::dataRevision);
            assertEquals(1, executor.remainingRows());
        }
    }

    private static final class AppendState extends MixedUnitTest.FakeState {
        void tables(List<TiDBTable> tables) {
            setSchema(new TiDBSchema(tables));
        }
    }

    static final class Fixture implements AutoCloseable {
        final Connection database;
        final AppendState state = new AppendState();
        final List<TiDBTable> tables = new ArrayList<>();
        final JsonObject packet;
        String engines = "tikv,tiflash";
        String snapshot = "";
        boolean active;
        boolean insertWarning;
        boolean injectUpdate;
        boolean loseCommitReply;
        boolean failSetup;

        Fixture() throws Exception {
            database = DriverManager
                    .getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
            try (Statement statement = database.createStatement()) {
                statement.execute(
                        "CREATE TABLE t0(k BIGINT PRIMARY KEY,n DECIMAL(38,2),s VARCHAR(1000),b VARBINARY(8),g BIGINT AS (k+1))");
                statement.execute("CREATE TABLE t1(k INT)");
                statement.execute("INSERT INTO t0(k,n,s) VALUES (1,1.23,'original')");
            }
            List<TiDBColumn> columns = List.of(column("k", TiDBCompositeDataType.getInt(8), "bigint", ""),
                    column("n", new TiDBCompositeDataType(TiDBDataType.DECIMAL), "decimal(38,2)", ""),
                    column("s", new TiDBCompositeDataType(TiDBDataType.TEXT), "varchar(1000)", ""),
                    column("b", new TiDBCompositeDataType(TiDBDataType.BLOB), "varbinary(8)", ""),
                    column("g", TiDBCompositeDataType.getInt(8), "bigint", "VIRTUAL GENERATED"));
            TiDBTable table = new TiDBTable("t0", columns, List.of(), false);
            columns.forEach(c -> c.setTable(table));
            tables.add(table);
            tables.add(MixedObservationTest.table("t1"));
            state.tables(tables);
            state.setDatabaseName("append_fixture");
            state.setState(new StateToReproduce("append_fixture", new TiDBProvider()));
            Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                        if (method.getName().equals("createStatement")) {
                            return statement();
                        }
                        if (method.getName().equals("prepareStatement")) {
                            return prepared((String) args[0]);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            state.setConnection(new SQLConnection(connection));
            packet = json(Files.readString(Path.of("docs/data-preparation-v1/01-state.json")));
            packet.addProperty("request_id", "manual_1");
            packet.add("schema",
                    TiDBMixedMetadata.collect(new TiDBMixedSupport(state), "append_fixture", tables, 0).schema);
            packet.add("samples", new JsonArray());
        }

        private static TiDBColumn column(String name, TiDBCompositeDataType type, String sqlType, String extra) {
            return new TiDBColumn(name, type, name.equals("k"), !name.equals("k"), false, sqlType, null, extra);
        }

        TiDBMixedAppendExecutor executor(int rows) {
            packet.getAsJsonObject("remaining_budget").addProperty("insert_rows", rows);
            return new TiDBMixedAppendExecutor(state, packet, 100);
        }

        String scalar(String sql) throws SQLException {
            try (Statement statement = database.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
                rows.next();
                return rows.getString(1);
            }
        }

        Statement statement() throws SQLException {
            Statement real = database.createStatement();
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Statement.class },
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("setFetchSize")) {
                            return null;
                        }
                        if (name.equals("executeQuery")) {
                            String sql = (String) args[0];
                            if (sql.startsWith("SELECT @@tidb_snapshot")) {
                                return MixedObservationTest.rows(
                                        new String[] { "snapshot", "engines", "transaction_ts", "autocommit" },
                                        new Object[] { snapshot, engines, active ? "1234567" : "0", "1" });
                            }
                            if (sql.startsWith("SELECT TIDB_CURRENT_TSO")) {
                                return MixedObservationTest.rows(new String[] { "tso" },
                                        new Object[] { active ? "1234567" : "0" });
                            }
                            if (sql.startsWith("SELECT DATABASE()")) {
                                return MixedObservationTest.rows(
                                        new String[] { "db", "autocommit", "transaction_ts", "snapshot", "engines" },
                                        new Object[] { "append_fixture", "1", active ? "123" : "0", snapshot,
                                                engines });
                            }
                            if (sql.contains("information_schema.")) {
                                return metadata(sql);
                            }
                            if (sql.startsWith("SHOW CREATE")) {
                                return MixedObservationTest.rows(new String[] { "create table" },
                                        new Object[] { "CREATE TABLE t0 (k BIGINT)" });
                            }
                        }
                        if (name.equals("execute")) {
                            String sql = (String) args[0];
                            if (sql.startsWith("SET SESSION")) {
                                if (failSetup && sql.endsWith("='tikv'")) {
                                    throw new SQLException("injected session setup failure", "HY000", 0);
                                }
                                String value = sql.substring(sql.indexOf('=') + 1);
                                if (value.startsWith("CONVERT(X'")) {
                                    String hex = value.substring(10, value.indexOf('\'', 10));
                                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                                    for (int i = 0; i < hex.length(); i += 2) {
                                        bytes.write(Integer.parseInt(hex.substring(i, i + 2), 16));
                                    }
                                    value = bytes.toString(java.nio.charset.StandardCharsets.UTF_8);
                                } else {
                                    value = value.substring(1, value.length() - 1);
                                }
                                if (sql.contains("tidb_snapshot")) {
                                    snapshot = value;
                                } else {
                                    engines = value;
                                }
                                return false;
                            }
                            if (sql.equals("BEGIN")) {
                                active = true;
                            }
                            if (sql.equals("COMMIT") || sql.equals("ROLLBACK")) {
                                active = false;
                            }
                            if (sql.equals("COMMIT") && loseCommitReply) {
                                real.execute(sql);
                                throw new SQLException("injected lost COMMIT acknowledgement", "08006", 0);
                            }
                        }
                        return invoke(real, method, args);
                    });
        }

        PreparedStatement prepared(String sql) throws SQLException {
            PreparedStatement real = database.prepareStatement(sql);
            return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { PreparedStatement.class }, (proxy, method, args) -> {
                        if (method.getName().equals("setFetchSize")) {
                            return null; // Simulate Connector/J's streaming hint, which H2 does not implement.
                        }
                        if (method.getName().equals("getWarnings") && insertWarning) {
                            return new SQLWarning("injected truncation", "01000", 1265);
                        }
                        Object result = invoke(real, method, args);
                        if (method.getName().equals("executeUpdate") && injectUpdate) {
                            try (Statement statement = database.createStatement()) {
                                statement.executeUpdate("UPDATE t0 SET s='changed' WHERE k=1");
                            }
                        }
                        return result;
                    });
        }

        private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException error) {
                throw error.getCause();
            }
        }

        ResultSet metadata(String sql) throws SQLException {
            boolean first = sql.contains(TiDBMixedSupport.literal("t0"));
            if (sql.contains("information_schema.columns")) {
                List<Map<String, String>> rows = new ArrayList<>();
                for (TiDBColumn column : tables.get(first ? 0 : 1).getColumns()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("column_name", column.getName());
                    row.put("column_type", first ? column.getSqlType() : "int");
                    row.put("is_nullable", column.isNullable() ? "YES" : "NO");
                    row.put("column_default", null);
                    row.put("extra", column.getExtra());
                    row.put("generation_expression", column.isGenerated() ? "`k`+1" : "");
                    rows.add(row);
                }
                return catalog(rows);
            }
            if (sql.contains("information_schema.statistics") && first) {
                return catalog(List.of(
                        Map.of("index_name", "PRIMARY", "non_unique", "0", "seq_in_index", "1", "column_name", "k")));
            }
            return catalog(List.of());
        }

        private static ResultSet catalog(List<Map<String, String>> input) throws SQLException {
            javax.sql.rowset.CachedRowSet rows = javax.sql.rowset.RowSetProvider.newFactory().createCachedRowSet();
            javax.sql.rowset.RowSetMetaDataImpl metadata = new javax.sql.rowset.RowSetMetaDataImpl();
            List<String> names = input.isEmpty() ? List.of("unused") : new ArrayList<>(input.get(0).keySet());
            metadata.setColumnCount(names.size());
            for (int i = 0; i < names.size(); i++) {
                metadata.setColumnName(i + 1, names.get(i));
                metadata.setColumnLabel(i + 1, names.get(i));
                metadata.setColumnType(i + 1, java.sql.Types.VARCHAR);
            }
            rows.setMetaData(metadata);
            for (int j = input.size() - 1; j >= 0; j--) {
                rows.moveToInsertRow();
                for (int i = 0; i < names.size(); i++) {
                    rows.updateObject(i + 1, input.get(j).get(names.get(i)));
                }
                rows.insertRow();
                rows.moveToCurrentRow();
            }
            rows.beforeFirst();
            return rows;
        }

        @Override
        public void close() throws SQLException {
            database.close();
        }
    }
}
