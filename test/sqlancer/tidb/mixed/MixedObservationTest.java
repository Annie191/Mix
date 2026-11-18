package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

import sqlancer.SQLConnection;
import sqlancer.StateToReproduce;
import sqlancer.tidb.TiDBProvider;
import sqlancer.tidb.TiDBSchema.TiDBColumn;
import sqlancer.tidb.TiDBSchema.TiDBCompositeDataType;
import sqlancer.tidb.TiDBSchema.TiDBTable;
import sqlancer.tidb.ast.TiDBAggregate;
import sqlancer.tidb.ast.TiDBAggregate.TiDBAggregateFunction;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation.TiDBComparisonOperator;
import sqlancer.tidb.ast.TiDBColumnReference;
import sqlancer.tidb.ast.TiDBConstant;
import sqlancer.tidb.ast.TiDBExpression;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.ast.TiDBSelect;
import sqlancer.tidb.ast.TiDBTableReference;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

/** Executes the actual probe SQL in H2; TiDB session statements alone are simulated. */
class MixedObservationTest {
    static TiDBTable table(String name) {
        TiDBColumn key = new TiDBColumn("k", TiDBCompositeDataType.getInt(4), false, true, false);
        TiDBTable table = new TiDBTable(name, List.of(key), List.of(), false);
        key.setTable(table);
        return table;
    }

    static Candidate candidate(List<TiDBTable> tables, TiDBJoin.JoinType type, TiDBComparisonOperator operator,
            boolean reversed) {
        TiDBExpression left = new TiDBColumnReference(tables.get(0).getColumns().get(0));
        TiDBExpression right = new TiDBColumnReference(tables.get(1).getColumns().get(0));
        TiDBJoin join = new TiDBJoin(new TiDBTableReference(tables.get(0)), new TiDBTableReference(tables.get(1)), type,
                new TiDBBinaryComparisonOperation(reversed ? right : left, reversed ? left : right, operator));
        TiDBSelect ast = new TiDBSelect();
        ast.setFetchColumns(List.of(left, right));
        ast.setFromList(List.of());
        ast.setJoinList(List.of(join));
        return new Candidate(ast, tables, List.of());
    }

    static JsonObject observation(TiDBMixedObservations facts, String id) {
        return facts.observations.asList().stream().map(e -> e.getAsJsonObject())
                .filter(e -> id.equals(e.get("capability_id").getAsString())).findFirst().orElseThrow();
    }

    static String count(TiDBMixedObservations facts, String id) {
        JsonObject observation = observation(facts, id);
        assertEquals("exact", observation.get("status").getAsString(), observation.toString());
        return observation.getAsJsonObject("value").get("count").getAsString();
    }

    static TiDBMixedObservations collect(Fixture fixture, Candidate candidate, int limit) throws SQLException {
        TiDBMixedObservations facts = new TiDBMixedObservations(candidate, "query_test", 2, 7);
        facts.collect(fixture.io, facts.allIds(), new TiDBMixedObservations.Budget(100, limit, 60_000));
        return facts;
    }

    @Test
    void outerJoinRowsAreNotMatchingPairsAndNullKeysAreUnmatched() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TiDBMixedObservations facts = collect(fixture, fixture.query(TiDBJoin.JoinType.LEFT), 64);
            assertEquals("4", count(facts, "j0_pairs_before"));
            assertEquals("2", count(facts, "j0_left_unmatched"));
            assertEquals("1", count(facts, "j0_right_unmatched"));
            assertEquals("6", count(facts, "query_input_before"));
            assertEquals("2", count(facts, "j0_left_unmatched_after"));
            assertEquals("0", count(facts, "j0_right_unmatched_after"));
            assertEquals("4", count(facts, "rows_t0"));
            assertEquals("1", count(facts, "nulls_t0.k"));
            assertEquals("2", count(facts, "j0_left_repeated"));
            assertEquals("2", count(facts, "j0_right_repeated"));
            assertEquals("1", count(facts, "j0_duplicate_after"));
            assertEquals("1234567", facts.snapshot);
            assertTrue(facts.observations.asList().stream()
                    .allMatch(e -> e.getAsJsonObject().get("data_revision").getAsLong() == 7));
        }
    }

    @Test
    void whereCountsRespectOuterNullExtensionAndRetainEmptyCases() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Candidate query = fixture.query(TiDBJoin.JoinType.LEFT);
            query.ast.setWhereClause(
                    new TiDBBinaryComparisonOperation(new TiDBColumnReference(query.tables.get(1).getColumns().get(0)),
                            TiDBConstant.createIntConstant(10), TiDBComparisonOperator.GREATER));
            query = new Candidate(query.ast, query.tables, List.of());
            TiDBMixedObservations facts = collect(fixture, query, 64);
            assertEquals("4", count(facts, "j0_pairs_before"));
            assertEquals("0", count(facts, "j0_pairs_after"));
            assertEquals("0", count(facts, "query_input_after"));
            assertEquals("0", count(facts, "j0_left_unmatched_after"));
            assertTrue(observation(facts, "query_empty").getAsJsonObject("value").get("empty").getAsBoolean());
        }
    }

    @Test
    void rightJoinAndReversedEqualityPreserveDirection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Candidate query = candidate(fixture.tables, TiDBJoin.JoinType.RIGHT, TiDBComparisonOperator.EQUALS, true);
            TiDBMixedObservations facts = collect(fixture, query, 64);
            assertEquals("4", count(facts, "j0_pairs_before"));
            assertEquals("5", count(facts, "query_input_before"));
            assertEquals("0", count(facts, "j0_left_unmatched_after"));
            assertEquals("1", count(facts, "j0_right_unmatched_after"));
        }
    }

    @Test
    void duplicateCountsRetainInputMultiplicityAndFrequencyIncludesNull() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TiDBMixedObservations facts = collect(fixture, fixture.query(TiDBJoin.JoinType.INNER), 64);
            JsonObject frequency = observation(facts, "freq_t0.k");
            assertEquals("exact", frequency.get("status").getAsString());
            assertEquals(3, frequency.getAsJsonObject("value").getAsJsonArray("rows").size());
            assertTrue(frequency.toString().contains("\"type\":\"null\""));
            assertEquals("4", count(facts, "j0_pairs_after"));
            assertEquals("1", count(facts, "j0_duplicate_after"));
        }
    }

    @Test
    void boundedSamplesAndFrequenciesNeverClaimCompleteDistributions() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TiDBMixedObservations facts = collect(fixture, fixture.query(TiDBJoin.JoinType.LEFT), 1);
            assertEquals("sampled", observation(facts, "freq_t0.k").get("status").getAsString());
            assertEquals("sampled", observation(facts, "sample_t0").get("status").getAsString());
            assertFalse(facts.samples.get(0).getAsJsonObject().get("complete").getAsBoolean());
            assertEquals(1, facts.samples.get(0).getAsJsonObject().getAsJsonArray("rows").size());
            assertEquals("4", count(facts, "rows_t0"));
            assertEquals("exact", observation(facts, "query_empty").get("status").getAsString());
            assertFalse(observation(facts, "query_empty").getAsJsonObject("value").get("empty").getAsBoolean());
        }
    }

    @Test
    void groupSummarySeparatesInputRowsGroupsAndHavingSurvivors() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Candidate query = fixture.query(TiDBJoin.JoinType.LEFT);
            TiDBExpression key = new TiDBColumnReference(query.tables.get(0).getColumns().get(0));
            TiDBAggregate count = new TiDBAggregate(List.of(TiDBConstant.createIntConstant(1)),
                    TiDBAggregateFunction.COUNT);
            query.ast.setGroupByExpressions(List.of(key));
            query.ast.setFetchColumns(List.of(key, count));
            query.ast.setHavingClause(new TiDBBinaryComparisonOperation(count, TiDBConstant.createIntConstant(1),
                    TiDBComparisonOperator.GREATER));
            query = new Candidate(query.ast, query.tables, List.of());
            TiDBMixedObservations facts = collect(fixture, query, 64);
            assertEquals("6", count(facts, "query_input_after"));
            JsonObject before = observation(facts, "groups_before_having");
            JsonObject after = observation(facts, "groups_after_having");
            assertEquals("exact", before.get("status").getAsString(), before.toString());
            assertEquals("exact", after.get("status").getAsString(), after.toString());
            assertEquals("3", before.getAsJsonObject("value").getAsJsonArray("rows").get(0).getAsJsonArray().get(0)
                    .getAsJsonObject().get("value").getAsString());
            assertEquals("1", after.getAsJsonObject("value").getAsJsonArray("rows").get(0).getAsJsonArray().get(0)
                    .getAsJsonObject().get("value").getAsString());
            assertEquals("4", after.getAsJsonObject("value").getAsJsonArray("rows").get(0).getAsJsonArray().get(1)
                    .getAsJsonObject().get("value").getAsString());
        }
    }

    @Test
    void globalAggregateOnEmptyInputIsNonemptyWithoutClaimingGroups() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Candidate query = fixture.query(TiDBJoin.JoinType.INNER);
            query.ast.setWhereClause(new TiDBBinaryComparisonOperation(TiDBConstant.createIntConstant(1),
                    TiDBConstant.createIntConstant(0), TiDBComparisonOperator.EQUALS));
            query.ast.setFetchColumns(List
                    .of(new TiDBAggregate(List.of(TiDBConstant.createIntConstant(1)), TiDBAggregateFunction.COUNT)));
            query = new Candidate(query.ast, query.tables, List.of());
            TiDBMixedObservations facts = collect(fixture, query, 64);
            assertEquals("0", count(facts, "query_input_after"));
            assertFalse(observation(facts, "query_empty").getAsJsonObject("value").get("empty").getAsBoolean());
            assertEquals("unsupported", observation(facts, "groups_before_having").get("status").getAsString());
        }
    }

    @Test
    void complexAndNullSafeJoinsAreUnsupportedRatherThanZero() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Candidate query = candidate(fixture.tables, TiDBJoin.JoinType.INNER,
                    TiDBComparisonOperator.NULL_SAFE_EQUALS, false);
            TiDBMixedObservations facts = new TiDBMixedObservations(query, "q", 0, 0);
            facts.collect(fixture.io, List.of("j0_pairs_before", "j0_pairs_after"),
                    new TiDBMixedObservations.Budget(10, 10, 60_000));
            assertEquals("unsupported", observation(facts, "j0_pairs_before").get("status").getAsString());
            assertTrue(observation(facts, "j0_pairs_before").get("value").isJsonNull());
            assertFalse(facts.available().toString().contains("j0_pairs_before"));
        }
    }

    @Test
    void multipleFromItemsDoNotGiveWhereAFalseLocalJoinScope() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Candidate query = fixture.query(TiDBJoin.JoinType.LEFT);
            TiDBTable third = table("t2");
            query.ast.setFromList(List.of(new TiDBTableReference(third)));
            query = new Candidate(query.ast, List.of(query.tables.get(0), query.tables.get(1), third), List.of());
            TiDBMixedObservations facts = new TiDBMixedObservations(query, "q", 0, 0);
            facts.collect(fixture.io, List.of("j0_pairs_before", "j0_pairs_after"),
                    new TiDBMixedObservations.Budget(10, 10, 60_000));
            assertEquals("4", count(facts, "j0_pairs_before"));
            assertEquals("unsupported", observation(facts, "j0_pairs_after").get("status").getAsString());
        }
    }

    @Test
    void exhaustedBudgetIsUnknownAndDoesNotExecuteQueriesOrFabricateZero() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TiDBMixedObservations facts = new TiDBMixedObservations(fixture.query(TiDBJoin.JoinType.LEFT), "q", 0, 0);
            facts.collect(fixture.io, List.of("rows_t0", "rows_t1"), new TiDBMixedObservations.Budget(0, 10, 0));
            assertTrue(facts.observations.asList().stream()
                    .allMatch(e -> "unknown".equals(e.getAsJsonObject().get("status").getAsString())
                            && e.getAsJsonObject().get("value").isJsonNull()));
            assertTrue(fixture.executed.stream().noneMatch(sql -> sql.startsWith("SELECT COUNT")));
            assertEquals("", fixture.snapshot);
            assertEquals("tikv,tiflash", fixture.engines);
        }
    }

    @Test
    void smallBudgetsMeasureQueryFactsBeforeLargeColumnDistributions() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TiDBMixedObservations facts = new TiDBMixedObservations(fixture.query(TiDBJoin.JoinType.LEFT), "q", 0, 0);
            facts.collect(fixture.io, facts.allIds(), new TiDBMixedObservations.Budget(1, 10, 60_000));
            assertEquals("6", count(facts, "query_input_before"));
            assertEquals("unknown", observation(facts, "freq_t0.k").get("status").getAsString());
            assertTrue(facts.allIds().indexOf("j0_pairs_before") < facts.allIds().indexOf("freq_t0.k"));
        }
    }

    @Test
    void warningsAndExecutionErrorsRemainErrorsAndSessionIsRestored() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.warning = true;
            TiDBMixedObservations facts = new TiDBMixedObservations(fixture.query(TiDBJoin.JoinType.LEFT), "q", 0, 0);
            facts.collect(fixture.io, List.of("rows_t0"), new TiDBMixedObservations.Budget(10, 10, 60_000));
            assertEquals("error", observation(facts, "rows_t0").get("status").getAsString());
            assertEquals("", fixture.snapshot);
            assertEquals("tikv,tiflash", fixture.engines);
            fixture.warning = false;
            fixture.failure = true;
            facts = new TiDBMixedObservations(fixture.query(TiDBJoin.JoinType.LEFT), "q", 0, 0);
            facts.collect(fixture.io, List.of("rows_t0"), new TiDBMixedObservations.Budget(10, 10, 60_000));
            assertEquals("error", observation(facts, "rows_t0").get("status").getAsString());
            assertEquals("", fixture.snapshot);
        }
    }

    @Test
    void zeroSnapshotIsReplacedWithATransactionSnapshotForObservations() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.snapshot = "0";
            TiDBMixedObservations facts = new TiDBMixedObservations(fixture.query(TiDBJoin.JoinType.LEFT), "q", 0, 0);
            facts.collect(fixture.io, List.of("rows_t0"), new TiDBMixedObservations.Budget(10, 10, 60_000));
            assertEquals("1234567", facts.snapshot);
            assertEquals("exact", observation(facts, "rows_t0").get("status").getAsString());
            assertTrue(
                    fixture.executed.indexOf("BEGIN") < fixture.executed.indexOf("SELECT TIDB_CURRENT_TSO() AS tso"));
            assertEquals("0", fixture.snapshot);
            assertFalse(fixture.active);
        }
    }

    @Test
    void existingSnapshotIsReusedAndInvalidRequestsFailBeforeSessionChanges() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.snapshot = "11111";
            fixture.io.snapshot = "11111";
            TiDBMixedObservations facts = new TiDBMixedObservations(fixture.query(TiDBJoin.JoinType.LEFT), "q", 0, 0);
            TiDBMixedObservations.Budget budget = new TiDBMixedObservations.Budget(10, 10, 60_000);
            assertThrows(IllegalArgumentException.class, () -> facts.collect(fixture.io, List.of("DROP t0"), budget));
            assertTrue(fixture.executed.isEmpty());
            assertThrows(IllegalArgumentException.class,
                    () -> facts.collect(fixture.io, List.of("rows_t0", "rows_t0"), budget));
            facts.collect(fixture.io, List.of("rows_t0"), budget);
            assertEquals("11111", facts.snapshot);
            assertEquals("11111", fixture.snapshot);
            assertEquals("11111", fixture.io.snapshot);
        }
    }

    @Test
    void generatedColumnsAreExcludedFromGuidedSelection() {
        TiDBColumn placeholder = new TiDBColumn("temporary", null, false, false, false);
        assertNull(placeholder.getType());
        assertEquals("UNKNOWN", placeholder.getSqlType());
        TiDBColumn generated = new TiDBColumn("g", TiDBCompositeDataType.getInt(4), false, true, false, "int", null,
                "VIRTUAL GENERATED");
        TiDBTable table = new TiDBTable("t", List.of(generated), List.of(), false);
        generated.setTable(table);
        assertTrue(generated.isGenerated());
        assertNull(TiDBMixedGeneration.integerColumn(table));
        TiDBColumn writable = new TiDBColumn("k", TiDBCompositeDataType.getInt(8), true, false, false,
                "bigint unsigned", null, "auto_increment");
        table = new TiDBTable("t", List.of(generated, writable), List.of(), false);
        writable.setTable(table);
        assertEquals(writable, TiDBMixedGeneration.integerColumn(table));
        assertTrue(writable.isAutoIncrement());
        assertEquals("bigint unsigned", writable.getSqlType());
    }

    @Test
    void showCreateSplittingRespectsStringsNestedExpressionsAndCheckComments() {
        String ddl = "CREATE TABLE t (`s` varchar(20) DEFAULT 'a,b', `g` int AS ((1+2)),"
                + " CONSTRAINT `c``k` CHECK ((`s` <> ',CHECK(x)')) /*!80016 NOT ENFORCED */)";
        assertEquals(3, TiDBMixedMetadata.clauses(ddl).size());
        assertTrue(TiDBMixedMetadata.clauses(ddl).get(2).endsWith("/*!80016 NOT ENFORCED */"));
        assertThrows(IllegalArgumentException.class, () -> TiDBMixedMetadata.clauses("CREATE TABLE t ("));
    }

    @Test
    void streamingValuesPreserveDecimalPrecisionBinaryBytesAndNull() throws Exception {
        try (Fixture fixture = new Fixture()) {
            TiDBMixedReadOnly.Result result = TiDBMixedReadOnly.read(fixture.io,
                    "SELECT CAST(123456789012345678901234567890.01 AS DECIMAL(38,2)) AS d,"
                            + " X'00FF' AS b, CAST(NULL AS INTEGER) AS n",
                    2, 5);
            assertTrue(result.complete);
            assertEquals("123456789012345678901234567890.01",
                    result.rows.get(0).getAsJsonArray().get(0).getAsJsonObject().get("value").getAsString());
            assertEquals("AP8=",
                    result.rows.get(0).getAsJsonArray().get(1).getAsJsonObject().get("value").getAsString());
            assertEquals("null",
                    result.rows.get(0).getAsJsonArray().get(2).getAsJsonObject().get("type").getAsString());
        }
    }

    static CachedRowSet rows(String[] names, Object[] values) throws SQLException {
        CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(names.length);
        for (int i = 0; i < names.length; i++) {
            metadata.setColumnName(i + 1, names[i]);
            metadata.setColumnLabel(i + 1, names[i]);
            metadata.setColumnType(i + 1, Types.VARCHAR);
        }
        rows.setMetaData(metadata);
        rows.moveToInsertRow();
        for (int i = 0; i < values.length; i++) {
            rows.updateObject(i + 1, values[i]);
        }
        rows.insertRow();
        rows.moveToCurrentRow();
        rows.beforeFirst();
        return rows;
    }

    static final class Fixture implements AutoCloseable {
        final Connection database;
        final List<TiDBTable> tables = List.of(table("t0"), table("t1"));
        final List<String> executed = new ArrayList<>();
        final TiDBMixedSupport io;
        String snapshot = "";
        String engines = "tikv,tiflash";
        boolean active;
        boolean warning;
        boolean failure;
        java.util.function.UnaryOperator<ResultSet> resultDecorator = result -> result;

        Fixture() throws SQLException {
            database = DriverManager
                    .getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
            try (Statement statement = database.createStatement()) {
                statement.execute("CREATE TABLE t0(k INT)");
                statement.execute("CREATE TABLE t1(k INT)");
                statement.execute("INSERT INTO t0 VALUES (1),(1),(NULL),(2)");
                statement.execute("INSERT INTO t1 VALUES (1),(1),(3)");
            }
            MixedUnitTest.FakeState state = new MixedUnitTest.FakeState();
            state.setState(new StateToReproduce("facts_fixture", new TiDBProvider()));
            Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                        if (method.getName().equals("createStatement")) {
                            return statement();
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            state.setConnection(new SQLConnection(connection));
            io = new TiDBMixedSupport(state);
        }

        Candidate query(TiDBJoin.JoinType type) {
            return candidate(tables, type, TiDBComparisonOperator.EQUALS, false);
        }

        Statement statement() throws SQLException {
            Statement real = database.createStatement();
            boolean[] probe = { false };
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Statement.class },
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("setFetchSize")) {
                            assertEquals(Integer.MIN_VALUE, args[0]);
                            return null;
                        }
                        if (name.equals("getWarnings")) {
                            return warning && probe[0] ? new SQLWarning("fixture warning", "01000", 1) : null;
                        }
                        if (name.equals("execute") || name.equals("executeQuery")) {
                            String sql = (String) args[0];
                            probe[0] = sql.startsWith("SELECT COUNT");
                            executed.add(sql);
                            if (sql.startsWith("SELECT @@tidb_snapshot")) {
                                return rows(new String[] { "snapshot", "engines", "transaction_ts", "autocommit" },
                                        new Object[] { snapshot, engines, active ? "1234567" : "0", "1" });
                            }
                            if (sql.startsWith("SELECT TIDB_CURRENT_TSO")) {
                                return rows(new String[] { "tso" }, new Object[] { active ? "1234567" : "0" });
                            }
                            if (sql.startsWith("SET SESSION ")) {
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
                            } else if (sql.equals("ROLLBACK")) {
                                active = false;
                            }
                            if (failure && sql.startsWith("SELECT")) {
                                throw new SQLException("fixture error", "HY000", 3024);
                            }
                        }
                        try {
                            Object result = method.invoke(real, args);
                            return result instanceof ResultSet ? resultDecorator.apply((ResultSet) result) : result;
                        } catch (InvocationTargetException error) {
                            throw error.getCause();
                        }
                    });
        }

        @Override
        public void close() throws SQLException {
            database.close();
        }
    }
}
