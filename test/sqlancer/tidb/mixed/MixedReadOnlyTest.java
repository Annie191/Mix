package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import sqlancer.tidb.ast.TiDBConstant;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

/** Broken/missing JDBC labels must not prevent observations or require decoding an existence probe. */
class MixedReadOnlyTest {
    @Test
    void driverBitMappingCannotTurnNumericCatalogValuesIntoBase64() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            TiDBMixedSupport io = new TiDBMixedSupport(fixture.state);
            for (int reported : List.of(java.sql.Types.BIT, java.sql.Types.BOOLEAN)) {
                ResultSetMetaData metadata = (ResultSetMetaData) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] { ResultSetMetaData.class }, (proxy, method, args) -> {
                            switch (method.getName()) {
                            case "getColumnType":
                                return reported;
                            case "getTableName":
                                return "T0";
                            case "getColumnName":
                                return (int) args[0] == 1 ? "k" : "b";
                            default:
                                throw new AssertionError(method.getName());
                            }
                        });
                ResultSet rows = (ResultSet) Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] { ResultSet.class }, (proxy, method, args) -> {
                            if (method.getName().equals("getString")) {
                                return "2";
                            }
                            if (method.getName().equals("getBytes")) {
                                return new byte[] { 2 };
                            }
                            if (method.getName().equals("getBoolean")) {
                                return true;
                            }
                            throw new AssertionError(method.getName());
                        });
                assertEquals(MixedAppendTest.json("{\"type\":\"integer\",\"value\":\"2\"}"),
                        TiDBMixedReadOnly.value(io, rows, metadata, 1));
                JsonObject binary = TiDBMixedReadOnly.value(io, rows, metadata, 2);
                assertEquals(reported == java.sql.Types.BIT ? "binary" : "boolean", binary.get("type").getAsString());
            }
        }
    }

    @Test
    void missingLabelsUseStablePositionsWhileTypesValuesAndSamplingRemainIntact() throws Exception {
        try (MixedObservationTest.Fixture fixture = new MixedObservationTest.Fixture()) {
            fixture.resultDecorator = rows -> decorate(rows, false, new int[3]);
            String sql = "SELECT k AS a, k AS b, k AS c, CAST(1.25 AS DECIMAL(4,2)) AS MiXeD FROM t0";
            TiDBMixedReadOnly.Result first = TiDBMixedReadOnly.read(fixture.io, sql, 1, 5);
            TiDBMixedReadOnly.Result second = TiDBMixedReadOnly.read(fixture.io, sql, 1, 5);
            assertEquals(List.of("column_1", "column_2", "column_3", "mixed"), first.columns);
            assertEquals(first.columns, second.columns);
            assertEquals(first.rows, second.rows);
            assertFalse(first.complete);
            assertEquals(1, first.rows.size());
            assertEquals("1.25",
                    first.rows.get(0).getAsJsonArray().get(3).getAsJsonObject().get("value").getAsString());
            TiDBMixedReadOnly.Result empty = TiDBMixedReadOnly.read(fixture.io, sql + " WHERE FALSE", 1, 5);
            assertEquals(first.columns, empty.columns);
            assertTrue(empty.rows.isEmpty());
            assertTrue(empty.complete);
        }
    }

    @Test
    void queryEmptyNeverReadsMetadataOrCellsAndOnlyAdvancesOnce() throws Exception {
        try (MixedObservationTest.Fixture fixture = new MixedObservationTest.Fixture()) {
            int[] calls = new int[3];
            fixture.resultDecorator = rows -> decorate(rows, true, calls);
            TiDBMixedObservations facts = new TiDBMixedObservations(fixture.query(TiDBJoin.JoinType.INNER), "q", 0, 0);
            facts.collect(fixture.io, List.of("query_empty"), new TiDBMixedObservations.Budget(1, 64, 10_000));
            JsonObject observed = MixedObservationTest.observation(facts, "query_empty");
            assertEquals("exact", observed.get("status").getAsString());
            assertFalse(observed.getAsJsonObject("value").get("empty").getAsBoolean());
            assertEquals(1, calls[0]);
            assertEquals(1, calls[1]);
            assertEquals(0, calls[2]);
            assertEquals("", fixture.snapshot);
            assertEquals("tikv,tiflash", fixture.engines);
        }
    }

    @Test
    void emptyOriginalQueryIsExactAndRestoresSnapshotWithoutDecoding() throws Exception {
        try (MixedObservationTest.Fixture fixture = new MixedObservationTest.Fixture()) {
            int[] calls = new int[3];
            fixture.resultDecorator = rows -> decorate(rows, true, calls);
            Candidate original = fixture.query(TiDBJoin.JoinType.INNER);
            original.ast.setWhereClause(TiDBConstant.createBooleanConstant(false));
            Candidate query = new Candidate(original.ast, original.tables, original.guidedColumns);
            TiDBMixedObservations facts = new TiDBMixedObservations(query, "q", 0, 0);
            facts.collect(fixture.io, List.of("query_empty"), new TiDBMixedObservations.Budget(1, 1, 10_000));
            JsonObject observed = MixedObservationTest.observation(facts, "query_empty");
            assertEquals("exact", observed.get("status").getAsString());
            assertTrue(observed.getAsJsonObject("value").get("empty").getAsBoolean());
            assertEquals(1, calls[0]);
            assertEquals(1, calls[1]);
            assertEquals(0, calls[2]);
            assertEquals("", fixture.snapshot);
            assertFalse(fixture.active);
        }
    }

    @Test
    void existenceDoesNotDecodeNanAndUsesActualAggregateAndHavingOutput() throws Exception {
        try (MixedObservationTest.Fixture fixture = new MixedObservationTest.Fixture()) {
            fixture.resultDecorator = rows -> decorate(rows, true, new int[3]);
            assertFalse(TiDBMixedReadOnly.isEmpty(fixture.io, "SELECT CAST('NaN' AS DOUBLE)", new JsonArray(), 5));
            assertFalse(
                    TiDBMixedReadOnly.isEmpty(fixture.io, "SELECT COUNT(*) FROM t0 WHERE FALSE", new JsonArray(), 5));
            assertTrue(TiDBMixedReadOnly.isEmpty(fixture.io, "SELECT COUNT(*) FROM t0 WHERE FALSE HAVING COUNT(*) > 0",
                    new JsonArray(), 5));
        }
    }

    @Test
    void missingLabelsDoNotAbortCountOrSampleObservations() throws Exception {
        try (MixedObservationTest.Fixture fixture = new MixedObservationTest.Fixture()) {
            fixture.resultDecorator = rows -> decorate(rows, false, new int[3]);
            TiDBMixedObservations facts = new TiDBMixedObservations(fixture.query(TiDBJoin.JoinType.INNER), "q", 0, 0);
            facts.collect(fixture.io, List.of("rows_t0", "sample_t0"), new TiDBMixedObservations.Budget(2, 1, 10_000));
            assertEquals("4", MixedObservationTest.count(facts, "rows_t0"));
            JsonObject sample = MixedObservationTest.observation(facts, "sample_t0");
            assertEquals("sampled", sample.get("status").getAsString());
            assertEquals("column_1", sample.getAsJsonObject("value").getAsJsonArray("columns").get(0).getAsString());
            assertEquals(1, sample.getAsJsonObject("value").getAsJsonArray("rows").size());
        }
    }

    @Test
    void existenceWarningsStillFailAndCloseTheResult() throws Exception {
        try (MixedObservationTest.Fixture fixture = new MixedObservationTest.Fixture()) {
            fixture.warning = true;
            int[] calls = new int[3];
            fixture.resultDecorator = rows -> decorate(rows, true, calls);
            SQLException warning = assertThrows(SQLException.class,
                    () -> TiDBMixedReadOnly.isEmpty(fixture.io, "SELECT COUNT(*) FROM t0", new JsonArray(), 5));
            assertEquals("01000", warning.getSQLState());
            assertEquals(1, calls[0]);
            assertEquals(1, calls[1]);
            assertEquals(0, calls[2]);
        }
    }

    @Test
    void failedExistenceQueryIsAnErrorRatherThanEmptyAndRestoresSession() throws Exception {
        try (MixedObservationTest.Fixture fixture = new MixedObservationTest.Fixture()) {
            fixture.failure = true;
            TiDBMixedObservations facts = new TiDBMixedObservations(fixture.query(TiDBJoin.JoinType.INNER), "q", 0, 0);
            facts.collect(fixture.io, List.of("query_empty"), new TiDBMixedObservations.Budget(1, 1, 10_000));
            JsonObject observed = MixedObservationTest.observation(facts, "query_empty");
            assertEquals("error", observed.get("status").getAsString());
            assertTrue(observed.get("value").isJsonNull());
            assertEquals("", fixture.snapshot);
            assertEquals("tikv,tiflash", fixture.engines);
        }
    }

    private static ResultSet decorate(ResultSet rows, boolean forbidDecoding, int[] calls) {
        return (ResultSet) Proxy.newProxyInstance(MixedReadOnlyTest.class.getClassLoader(),
                new Class<?>[] { ResultSet.class }, (proxy, method, args) -> {
                    if (method.getName().equals("next")) {
                        calls[0]++;
                    } else if (method.getName().equals("close")) {
                        calls[1]++;
                    } else if (method.getName().startsWith("get")) {
                        calls[2]++;
                        assertFalse(forbidDecoding, "existence probe must not call " + method.getName());
                    }
                    try {
                        Object value = method.invoke(rows, args);
                        if (method.getName().equals("getMetaData")) {
                            return Proxy.newProxyInstance(MixedReadOnlyTest.class.getClassLoader(),
                                    new Class<?>[] { ResultSetMetaData.class }, (metadata, accessor, arguments) -> {
                                        if (accessor.getName().equals("getColumnLabel")) {
                                            switch ((int) arguments[0]) {
                                            case 1:
                                                return null;
                                            case 2:
                                                return "";
                                            case 3:
                                                return " \t ";
                                            default:
                                                return "MiXeD";
                                            }
                                        }
                                        try {
                                            return accessor.invoke(value, arguments);
                                        } catch (InvocationTargetException error) {
                                            throw error.getCause();
                                        }
                                    });
                        }
                        return value;
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }
}
