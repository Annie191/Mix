package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class MixedPlannedGroupsTest {
    private static JsonObject fixture() throws Exception {
        return JsonParser.parseString(Files.readString(Path.of("docs/data-preparation-v2/seed57-group-plan.json")))
                .getAsJsonObject();
    }

    private static void alignCompositeKeys(JsonObject plan, String value) {
        for (JsonElement entry : plan.getAsJsonArray("operations")) {
            JsonArray rows = entry.getAsJsonObject().getAsJsonArray("rows");
            for (int i = 0; i < rows.size(); i++) {
                JsonObject row = rows.get(i).getAsJsonObject();
                row.getAsJsonObject("c4").addProperty("value", value);
                row.getAsJsonObject("c1").addProperty("value", Integer.toString(i));
            }
        }
    }

    @Test
    void actualSeed57PlanIsRejectedBeforeWritingAndCompositeKeyGuidanceNamesTheFreeComponent() throws Exception {
        JsonObject fixture = fixture();
        JsonObject state = fixture.getAsJsonObject("state");
        JsonObject plan = fixture.getAsJsonObject("plan");
        JsonObject bound = TiDBMixedPlannedGroups.analyze(plan, state);
        assertEquals("proved_upper_bound", bound.get("status").getAsString());
        assertEquals("1", bound.get("upper_bound").getAsString());
        TiDBDataPreparationProtocol.ValidationException failure = assertThrows(
                TiDBDataPreparationProtocol.ValidationException.class,
                () -> TiDBMixedPreparationSemantics.validateGoals(plan, state));
        assertEquals("GROUP_PLAN_CANNOT_REACH_GOAL", failure.code);
        assertEquals("$.structured_goals[3].expected", failure.details.get("path").getAsString());
        JsonArray guidance = TiDBMixedPreparationSemantics.analyze(state).getAsJsonArray("composite_key_guidance");
        assertEquals(2, guidance.size());
        assertTrue(guidance.toString().contains("t2.c4"));
        assertTrue(guidance.toString().contains("t2.c1"));
        alignCompositeKeys(plan, "700000001");
        assertEquals("4", TiDBMixedPlannedGroups.analyze(plan, state).get("upper_bound").getAsString());
        TiDBMixedPreparationSemantics.validateGoals(plan, state);
        for (String table : List.of("t0", "t2")) {
            state.getAsJsonObject("query").getAsJsonArray("group_by").add(table + ".c1");
        }
        assertThrows(TiDBDataPreparationProtocol.ValidationException.class,
                () -> TiDBMixedPreparationSemantics.validateGoals(plan, state));
    }

    @Test
    void incompleteOrStaleSamplesNeverProveImpossibleGoals() throws Exception {
        for (boolean stale : List.of(true, false)) {
            JsonObject fixture = fixture();
            JsonObject state = fixture.getAsJsonObject("state");
            if (stale) {
                state.addProperty("data_revision", 1);
            } else {
                state.getAsJsonArray("samples").get(0).getAsJsonObject().addProperty("complete", false);
            }
            JsonObject result = TiDBMixedPlannedGroups.analyze(fixture.getAsJsonObject("plan"), state);
            assertEquals("unverified", result.get("status").getAsString());
            assertTrue(result.get("upper_bound").isJsonNull());
            TiDBMixedPreparationSemantics.validateGoals(fixture.getAsJsonObject("plan"), state);
        }
    }

    @Test
    void floatingStorageRoundingCannotProduceAFalseDistinctGroupingProof() throws Exception {
        JsonObject fixture = fixture();
        JsonObject plan = fixture.getAsJsonObject("plan");
        alignCompositeKeys(plan, "9007199254740992");
        for (JsonElement entry : plan.getAsJsonArray("operations")) {
            entry.getAsJsonObject().getAsJsonArray("rows").get(1).getAsJsonObject().getAsJsonObject("c4")
                    .addProperty("value", "9007199254740993");
        }
        assertEquals("4", TiDBMixedPlannedGroups.analyze(plan, fixture.getAsJsonObject("state")).get("upper_bound")
                .getAsString());
    }

    @Test
    void unspecifiedGroupingValuesAndDisplayScaleTypesOnlyCoarsenTheBound() throws Exception {
        JsonObject fixture = fixture();
        JsonObject plan = fixture.getAsJsonObject("plan");
        JsonObject state = fixture.getAsJsonObject("state");
        for (JsonElement operation : plan.getAsJsonArray("operations")) {
            operation.getAsJsonObject().getAsJsonArray("rows").get(1).getAsJsonObject().remove("c4");
        }
        assertTrue(
                new java.math.BigInteger(TiDBMixedPlannedGroups.analyze(plan, state).get("upper_bound").getAsString())
                        .compareTo(java.math.BigInteger.valueOf(4)) >= 0);
        fixture = fixture();
        state = fixture.getAsJsonObject("state");
        for (JsonElement table : state.getAsJsonObject("schema").getAsJsonArray("tables")) {
            for (JsonElement column : table.getAsJsonObject().getAsJsonArray("columns")) {
                if (column.getAsJsonObject().get("name").getAsString().equals("c4")) {
                    column.getAsJsonObject().addProperty("sql_type", "double(10,2)");
                }
            }
        }
        assertTrue(new java.math.BigInteger(
                TiDBMixedPlannedGroups.analyze(fixture.getAsJsonObject("plan"), state).get("upper_bound").getAsString())
                        .compareTo(java.math.BigInteger.valueOf(4)) >= 0);
    }

    @Test
    void actualSqlShowsFourPairsInFourSingletonGroupsThenOneFourRowGroupWithLegalCompositeKeys() throws Exception {
        for (boolean corrected : List.of(false, true)) {
            JsonObject fixture = fixture();
            JsonObject plan = fixture.getAsJsonObject("plan");
            if (corrected) {
                alignCompositeKeys(plan, "700000001");
            }
            try (Connection connection = DriverManager
                    .getConnection("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
                    Statement statement = connection.createStatement()) {
                for (String table : List.of("t0", "t2")) {
                    statement.execute("CREATE TABLE " + table
                            + "(c0 VARBINARY(100),c1 INT,c2 VARCHAR(100),c3 INT,c4 DOUBLE,PRIMARY KEY(c4,c1))");
                }
                for (JsonElement entry : fixture.getAsJsonObject("state").getAsJsonArray("samples")) {
                    JsonObject sample = entry.getAsJsonObject();
                    for (JsonElement row : sample.getAsJsonArray("rows")) {
                        insert(connection, sample.get("table").getAsString(), row.getAsJsonArray());
                    }
                }
                for (JsonElement entry : plan.getAsJsonArray("operations")) {
                    JsonObject operation = entry.getAsJsonObject();
                    for (JsonElement row : operation.getAsJsonArray("rows")) {
                        JsonArray values = new JsonArray();
                        for (int i = 0; i < 5; i++) {
                            values.add(row.getAsJsonObject().get("c" + i));
                        }
                        insert(connection, operation.get("table").getAsString(), values);
                    }
                }
                try (ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM t2 JOIN t0 ON t2.c3=t0.c3")) {
                    rows.next();
                    assertEquals(4, rows.getInt(1));
                }
                try (ResultSet rows = statement.executeQuery(
                        "SELECT MAX(n),SUM(n) FROM (SELECT COUNT(*) n " + "FROM t2 LEFT JOIN t0 ON t2.c3=t0.c3 "
                                + "GROUP BY t2.c4,t0.c2,t2.c2,t2.c3,t2.c0,t0.c4,t0.c0) g")) {
                    rows.next();
                    assertEquals(corrected ? 4 : 1, rows.getInt(1));
                    assertEquals(6, rows.getInt(2));
                }
            }
        }
    }

    private static void insert(Connection connection, String table, JsonArray values) throws Exception {
        try (PreparedStatement insert = connection
                .prepareStatement("INSERT INTO " + table + " (c0,c1,c2,c3,c4) VALUES (?,?,?,?,?)")) {
            TiDBMixedReadOnly.bind(insert, values);
            assertEquals(1, insert.executeUpdate());
        }
    }
}
