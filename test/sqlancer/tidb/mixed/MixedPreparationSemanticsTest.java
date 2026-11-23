package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

class MixedPreparationSemanticsTest {
    static JsonObject state(int seed) throws Exception {
        return MixedActionSchemaTest.corpus().asList().stream().map(JsonElement::getAsJsonObject)
                .filter(item -> item.get("seed").getAsInt() == seed).findFirst().orElseThrow().getAsJsonObject("state")
                .deepCopy();
    }

    @Test
    void seed49CompleteGroupingKeysProveSingletonGroupsAndRejectOnlyImpossibleSizeGoals() throws Exception {
        JsonObject state = state(49);
        assertEquals("1", TiDBMixedPreparationSemantics.analyze(state).get("max_input_rows_per_group").getAsString());
        JsonObject plan = MixedAppendTest.json("{\"structured_goals\":[{\"capability_id\":\"groups_before_having\","
                + "\"field\":\"max_size\",\"comparison\":\"at_least\",\"expected\":\"4\"}]}");
        TiDBDataPreparationProtocol.ValidationException failure = assertThrows(
                TiDBDataPreparationProtocol.ValidationException.class,
                () -> TiDBMixedPreparationSemantics.validateGoals(plan, state));
        assertEquals("$.structured_goals[0].expected", failure.details.get("path").getAsString());
        plan.getAsJsonArray("structured_goals").get(0).getAsJsonObject().addProperty("comparison", "at_most");
        TiDBMixedPreparationSemantics.validateGoals(plan, state);
        plan.getAsJsonArray("structured_goals").get(0).getAsJsonObject().addProperty("comparison", "at_least");
        plan.getAsJsonArray("structured_goals").get(0).getAsJsonObject().addProperty("field", "group_count");
        TiDBMixedPreparationSemantics.validateGoals(plan, state);
    }

    @Test
    void incompleteMetadataNullableOrPrefixKeysAndUnknownJoinsCannotProveTheBound() throws Exception {
        for (int mutation = 0; mutation < 5; mutation++) {
            JsonObject state = state(49);
            JsonObject table = state.getAsJsonObject("schema").getAsJsonArray("tables").get(0).getAsJsonObject();
            if (mutation == 0) {
                state.getAsJsonObject("schema").addProperty("complete", false);
            } else if (mutation == 1) {
                table.addProperty("metadata_complete", false);
            } else if (mutation == 2) {
                table.getAsJsonArray("columns").get(2).getAsJsonObject().addProperty("nullable", true);
            } else if (mutation == 3) {
                table.getAsJsonArray("constraints").get(0).getAsJsonObject().getAsJsonArray("key_parts").get(0)
                        .getAsJsonObject().addProperty("prefix_length", 3);
            } else {
                state.getAsJsonObject("query").getAsJsonArray("joins").get(0).getAsJsonObject().addProperty("type",
                        "UNKNOWN");
            }
            assertTrue(TiDBMixedPreparationSemantics.analyze(state).get("max_input_rows_per_group").isJsonNull());
        }
    }

    @Test
    void outerNullExtensionCannotMultiplyAGroupContainingAllNonNullUniqueKeys() throws Exception {
        JsonObject state = state(49);
        state.getAsJsonObject("query").getAsJsonArray("joins").get(0).getAsJsonObject().addProperty("type", "RIGHT");
        assertEquals("1", TiDBMixedPreparationSemantics.analyze(state).get("max_input_rows_per_group").getAsString());
        JsonObject plan = MixedAppendTest.json("{\"structured_goals\":[{\"capability_id\":\"group_input_rows\","
                + "\"field\":\"count\",\"comparison\":\"at_least\",\"expected\":\"2\"}]}");
        assertThrows(TiDBDataPreparationProtocol.ValidationException.class,
                () -> TiDBMixedPreparationSemantics.validateGoals(plan, state));
    }

    @Test
    void seed51NaturalJoinRequiresFullBlobAgainstTheCompleteCharacterKey() throws Exception {
        JsonObject analysis = TiDBMixedPreparationSemantics.analyze(state(51));
        JsonArray keys = analysis.getAsJsonArray("natural_join_keys");
        assertEquals(1, keys.size());
        String types = keys.toString();
        assertTrue(types.contains("blob"));
        assertTrue(types.contains("char(1)"));
        assertFalse(types.contains("first_byte_is_equal"));
        assertTrue(analysis.get("max_input_rows_per_group").isJsonNull());
    }
}
