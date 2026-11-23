package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

class MixedPlanConstraintsTest {
    private static JsonObject packet(MixedAppendTest.Fixture fixture) {
        JsonObject state = MixedPreparationTest.packet(fixture, MixedPreparationTest.query(fixture, false));
        JsonObject sample = new JsonObject();
        for (String version : List.of("query_id", "schema_revision", "data_revision")) {
            sample.add(version, state.get(version));
        }
        sample.addProperty("id", "witness");
        sample.addProperty("table", "t0");
        sample.addProperty("complete", false);
        JsonArray columns = new JsonArray();
        columns.add("k");
        sample.add("columns", columns);
        JsonArray row = new JsonArray();
        row.add(MixedAppendTest.json("{\"type\":\"integer\",\"value\":\"1\"}"));
        JsonArray rows = new JsonArray();
        rows.add(row);
        sample.add("rows", rows);
        state.getAsJsonArray("samples").add(sample);
        return state;
    }

    @Test
    void currentSampleProvesConflictWithoutPretendingTheSampleIsComplete() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            JsonObject state = packet(fixture);
            JsonObject plan = MixedPreparationTest.response(state, MixedPreparationTest.append(1, true));
            TiDBDataPreparationProtocol.ValidationException error = assertThrows(
                    TiDBDataPreparationProtocol.ValidationException.class,
                    () -> TiDBMixedPlanConstraints.validate(plan, state));
            assertEquals("UNIQUE_KEY_CONFLICT", error.code);
            assertEquals("$.operations[0].rows[0]", error.details.get("path").getAsString());
            state.addProperty("data_revision", 1);
            TiDBMixedPlanConstraints.validate(plan, state); // A stale row cannot prove a current conflict.
        }
    }

    @Test
    void repeatedKeysAcrossOperationsAreRejectedButDifferentCompositeComponentsAreAllowed() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            JsonObject state = packet(fixture);
            JsonObject plan = MixedPreparationTest.response(state, MixedPreparationTest.append(12, true));
            plan.getAsJsonArray("operations").add(plan.getAsJsonArray("operations").get(0).deepCopy());
            assertThrows(TiDBDataPreparationProtocol.ValidationException.class,
                    () -> TiDBMixedPlanConstraints.validate(plan, state));
            JsonObject key = state.getAsJsonObject("schema").getAsJsonArray("tables").get(0).getAsJsonObject()
                    .getAsJsonArray("constraints").get(0).getAsJsonObject();
            key.getAsJsonArray("columns").add("n");
            key.getAsJsonArray("key_parts").add(MixedAppendTest
                    .json("{\"sequence\":2,\"column\":\"n\",\"expression\":null,\"prefix_length\":null}"));
            plan.getAsJsonArray("operations").get(1).getAsJsonObject().getAsJsonArray("rows").get(0).getAsJsonObject()
                    .getAsJsonObject("n").addProperty("value", "999");
            TiDBMixedPlanConstraints.validate(plan, state);
        }
    }

    @Test
    void actualTinyintRangeIsCheckedBeforeAnyAttemptAndCannotBeReadAsDisplayWidth() throws Exception {
        try (MixedAppendTest.Fixture fixture = new MixedAppendTest.Fixture()) {
            JsonObject state = packet(fixture);
            JsonObject column = state.getAsJsonObject("schema").getAsJsonArray("tables").get(0).getAsJsonObject()
                    .getAsJsonArray("columns").get(0).getAsJsonObject();
            column.addProperty("sql_type", "tinyint(1) unsigned");
            JsonObject plan = MixedPreparationTest.response(state, MixedPreparationTest.append(255, true));
            TiDBMixedPlanConstraints.validate(plan, state);
            plan.getAsJsonArray("operations").get(0).getAsJsonObject().getAsJsonArray("rows").get(0).getAsJsonObject()
                    .getAsJsonObject("k").addProperty("value", "256");
            TiDBDataPreparationProtocol.ValidationException error = assertThrows(
                    TiDBDataPreparationProtocol.ValidationException.class,
                    () -> TiDBMixedPlanConstraints.validate(plan, state));
            assertEquals("ROW_VALUE_OUT_OF_RANGE", error.code);
            assertEquals("255", error.details.getAsJsonObject("constraint_witness").get("maximum").getAsString());
        }
    }
}
