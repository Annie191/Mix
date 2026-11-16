package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetMetaDataImpl;
import javax.sql.rowset.RowSetProvider;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import sqlancer.SQLConnection;

/** Catalog acquisition fixtures, including partial failures; these are not live database metadata. */
class MixedMetadataTest {
    @Test
    void catalogTypesDefaultsAndGenerationAreKeptWithoutTypeSimplification() {
        Fixture fixture = new Fixture();
        Map<String, String> generated = new LinkedHashMap<>(fixture.columns.get(0));
        generated.put("column_name", "g");
        generated.put("generation_expression", "`k` + 1");
        generated.put("extra", "STORED GENERATED");
        fixture.columns.add(generated);
        TiDBMixedMetadata result = fixture.collect();
        assertTrue(result.schema.get("complete").getAsBoolean(), result.evidence.toString());
        JsonArray columns = table(result).getAsJsonArray("columns");
        assertEquals("decimal(38,2) unsigned", columns.get(0).getAsJsonObject().get("sql_type").getAsString());
        assertEquals("12345678901234567890.01",
                columns.get(0).getAsJsonObject().get("default_expression").getAsString());
        assertFalse(columns.get(0).getAsJsonObject().get("generated").getAsBoolean());
        assertTrue(columns.get(1).getAsJsonObject().get("generated").getAsBoolean());
        assertEquals("`k` + 1", columns.get(1).getAsJsonObject().get("generation_expression").getAsString());
    }

    @Test
    void compositeUniqueKeysAreOrderedAndPrefixAndExpressionPartsRemainExplicit() {
        Fixture fixture = new Fixture();
        fixture.indexes.add(index("uk", "2", "b", null, null));
        fixture.indexes.add(index("uk", "1", "a", null, null));
        fixture.indexes.add(index("prefix", "1", "s", "4", null));
        fixture.indexes.add(index("expr", "1", null, null, "lower(`s`)"));
        TiDBMixedMetadata result = fixture.collect();
        assertTrue(result.schema.get("complete").getAsBoolean(), result.evidence.toString());
        Map<String, JsonObject> constraints = constraints(result);
        assertEquals("[\"a\",\"b\"]", constraints.get("uk").getAsJsonArray("columns").toString());
        assertEquals("4", constraints.get("prefix").getAsJsonArray("key_parts").get(0).getAsJsonObject()
                .get("prefix_length").getAsString());
        assertEquals("lower(`s`)", constraints.get("expr").getAsJsonArray("key_parts").get(0).getAsJsonObject()
                .get("expression").getAsString());
        assertTrue(constraints.get("expr").getAsJsonArray("columns").isEmpty());
    }

    @Test
    void checkEnforcementComesFromTheMatchingDdlClauseIncludingQuotedNames() {
        Fixture fixture = new Fixture();
        fixture.ddl = "CREATE TABLE t0 (`k` int, CONSTRAINT `yes` CHECK ((k>0)),"
                + " CONSTRAINT `n``o` CHECK ((k<100)) /*!80016 NOT ENFORCED */)";
        fixture.checks.add(Map.of("constraint_name", "yes", "check_clause", "(k>0)"));
        fixture.checks.add(Map.of("constraint_name", "n`o", "check_clause", "(k<100)"));
        TiDBMixedMetadata result = fixture.collect();
        assertTrue(result.schema.get("complete").getAsBoolean(), result.evidence.toString());
        assertTrue(constraints(result).get("yes").get("enforced").getAsBoolean());
        assertFalse(constraints(result).get("n`o").get("enforced").getAsBoolean());
    }

    @Test
    void failedConstraintAcquisitionIsIncompleteRatherThanNoConstraints() {
        Fixture fixture = new Fixture();
        fixture.fail = "information_schema.statistics";
        TiDBMixedMetadata result = fixture.collect();
        assertFalse(result.schema.get("complete").getAsBoolean());
        assertFalse(table(result).get("metadata_complete").getAsBoolean());
        assertTrue(result.evidence.toString().contains("fixture unavailable"));
        assertTrue(result.evidence.get(0).containsKey("create"));
    }

    @Test
    void missingColumnCatalogFallsBackWithExplicitIncompleteStatus() {
        Fixture fixture = new Fixture();
        fixture.fail = "information_schema.columns";
        TiDBMixedMetadata result = fixture.collect();
        assertFalse(result.schema.get("complete").getAsBoolean());
        assertEquals("k", table(result).getAsJsonArray("columns").get(0).getAsJsonObject().get("name").getAsString());
        assertTrue(result.evidence.toString().contains("no actual column metadata"));
    }

    @Test
    void unrepresentedForeignKeysAndContradictoryCheckCatalogAreNotComplete() {
        Fixture fixture = new Fixture();
        fixture.ddl = "CREATE TABLE t0 (`k` int, CONSTRAINT `fk` FOREIGN KEY (`k`) REFERENCES p(`k`))";
        assertFalse(fixture.collect().schema.get("complete").getAsBoolean());
        fixture.ddl = "CREATE TABLE t0 (`k` int, CONSTRAINT `c` CHECK ((k>0)))";
        assertFalse(fixture.collect().schema.get("complete").getAsBoolean());
    }

    @Test
    void invalidIndexSequenceIsNotAcceptedAsAnAccurateCompositeKey() {
        Fixture fixture = new Fixture();
        fixture.indexes.add(index("uk", "2", "k", null, null));
        assertFalse(fixture.collect().schema.get("complete").getAsBoolean());
        assertTrue(TiDBMixedMetadata.valueKind("VARBINARY(8)").equals("binary"));
        assertEquals("integer", TiDBMixedMetadata.valueKind("bigint unsigned"));
    }

    private static JsonObject table(TiDBMixedMetadata result) {
        return result.schema.getAsJsonArray("tables").get(0).getAsJsonObject();
    }

    private static Map<String, JsonObject> constraints(TiDBMixedMetadata result) {
        Map<String, JsonObject> constraints = new LinkedHashMap<>();
        table(result).getAsJsonArray("constraints")
                .forEach(e -> constraints.put(e.getAsJsonObject().get("name").getAsString(), e.getAsJsonObject()));
        return constraints;
    }

    private static Map<String, String> index(String name, String sequence, String column, String prefix,
            String expression) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("index_name", name);
        result.put("non_unique", "0");
        result.put("seq_in_index", sequence);
        result.put("column_name", column);
        result.put("sub_part", prefix);
        result.put("expression", expression);
        return result;
    }

    private static CachedRowSet rows(List<Map<String, String>> input) throws SQLException {
        if (input.isEmpty()) {
            CachedRowSet empty = MixedObservationTest.rows(new String[] { "empty" }, new Object[] { "unused" });
            empty.next();
            empty.deleteRow();
            empty.beforeFirst();
            return empty;
        }
        List<String> names = new ArrayList<>(input.get(0).keySet());
        CachedRowSet rows = RowSetProvider.newFactory().createCachedRowSet();
        RowSetMetaDataImpl metadata = new RowSetMetaDataImpl();
        metadata.setColumnCount(names.size());
        for (int i = 0; i < names.size(); i++) {
            metadata.setColumnName(i + 1, names.get(i));
            metadata.setColumnLabel(i + 1, names.get(i));
            metadata.setColumnType(i + 1, Types.VARCHAR);
        }
        rows.setMetaData(metadata);
        for (int record = input.size() - 1; record >= 0; record--) {
            Map<String, String> row = input.get(record);
            rows.moveToInsertRow();
            for (int i = 0; i < names.size(); i++) {
                rows.updateObject(i + 1, row.get(names.get(i)));
            }
            rows.insertRow();
            rows.moveToCurrentRow();
        }
        rows.beforeFirst();
        return rows;
    }

    private static final class Fixture {
        final List<Map<String, String>> columns = new ArrayList<>();
        final List<Map<String, String>> indexes = new ArrayList<>();
        final List<Map<String, String>> checks = new ArrayList<>();
        String ddl = "CREATE TABLE t0 (`k` decimal(38,2) unsigned DEFAULT 12345678901234567890.01)";
        String fail = "";

        Fixture() {
            Map<String, String> column = new LinkedHashMap<>();
            column.put("column_name", "k");
            column.put("column_type", "decimal(38,2) unsigned");
            column.put("is_nullable", "YES");
            column.put("column_default", "12345678901234567890.01");
            column.put("extra", "DEFAULT_GENERATED");
            column.put("generation_expression", "");
            columns.add(column);
        }

        TiDBMixedMetadata collect() {
            Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { Connection.class }, (proxy, method, args) -> {
                        if (method.getName().equals("createStatement")) {
                            return statement();
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            MixedUnitTest.FakeState state = new MixedUnitTest.FakeState();
            state.setConnection(new SQLConnection(connection));
            return TiDBMixedMetadata.collect(new TiDBMixedSupport(state), "fixture_db",
                    List.of(MixedObservationTest.table("t0")), 0);
        }

        Statement statement() {
            return (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { Statement.class },
                    (proxy, method, args) -> {
                        switch (method.getName()) {
                        case "executeQuery":
                            String sql = (String) args[0];
                            if (!fail.isEmpty() && sql.contains(fail)) {
                                throw new SQLException("fixture unavailable", "HY000", 1105);
                            }
                            if (sql.contains("information_schema.columns")) {
                                return rows(columns);
                            }
                            if (sql.contains("information_schema.statistics")) {
                                return rows(indexes);
                            }
                            if (sql.contains("information_schema.tidb_check_constraints")) {
                                return rows(checks);
                            }
                            return rows(List.of(Map.of("create table", ddl)));
                        case "getWarnings":
                        case "setQueryTimeout":
                        case "close":
                            return null;
                        default:
                            throw new UnsupportedOperationException(method.getName());
                        }
                    });
        }
    }
}
