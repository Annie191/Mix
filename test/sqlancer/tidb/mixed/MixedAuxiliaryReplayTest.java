package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class MixedAuxiliaryReplayTest {
    @TempDir
    Path directory;

    private static JsonObject record(String sql) {
        JsonObject record = new JsonObject();
        record.addProperty("sql", sql);
        record.add("bindings", new JsonArray());
        record.addProperty("replay_status", "CAPTURED");
        record.add("exception_chain", new Gson().toJsonTree(TiDBMixedAuxiliaryErrors
                .exceptionChain(new SQLException("runtime error: nil pointer", "HY000", 1105))));
        return record;
    }

    @Test
    void replayDistinguishesSameSignatureDifferentErrorAndSuccessfulQuery() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID())) {
            JsonObject archive = record("SELECT 1/0");
            SQLException original = assertThrows(SQLException.class, () -> {
                try (PreparedStatement query = connection.prepareStatement(archive.get("sql").getAsString())) {
                    try (ResultSet rows = query.executeQuery()) {
                        rows.next();
                    }
                }
            });
            archive.add("exception_chain", new Gson().toJsonTree(TiDBMixedAuxiliaryErrors.exceptionChain(original)));
            JsonObject unchanged = archive.deepCopy();
            Map<String, Object> repeated = TiDBMixedAuxiliaryReplay.executeObserved(connection, archive);
            assertEquals("REPRODUCED_ERROR_SIGNATURE", repeated.get("status"));
            assertEquals(true, repeated.get("same_error_signature"));
            assertEquals(false, repeated.get("confirmed"));
            assertEquals(unchanged, archive);
            assertEquals("DIFFERENT_ERROR",
                    TiDBMixedAuxiliaryReplay.executeObserved(connection, record("SELECT 1/0")).get("status"));
            JsonObject success = record("SELECT ?");
            success.add("bindings", JsonParser.parseString("[{\"type\":\"integer\",\"value\":\"42\"}]"));
            Map<String, Object> result = TiDBMixedAuxiliaryReplay.executeObserved(connection, success);
            assertEquals("NOT_REPRODUCED", result.get("status"));
            assertEquals(true, result.get("has_row"));
            assertEquals(false, result.get("confirmed"));
        }
    }

    @Test
    void connectionFailureStillLeavesStructuredReplayResultAndCannotOverwriteArchive() throws Exception {
        Path archive = directory.resolve("error.json");
        Path result = directory.resolve("result.json");
        TiDBMixedSupport.save(archive, record("SELECT 1"));
        String original = Files.readString(archive);
        assertThrows(SQLException.class, () -> TiDBMixedAuxiliaryReplay
                .main(new String[] { "jdbc:missing-driver:", archive.toString(), result.toString() }));
        JsonObject saved = JsonParser.parseString(Files.readString(result)).getAsJsonObject();
        assertEquals("REPLAY_SETUP_FAILED", saved.get("status").getAsString());
        assertEquals("CONNECT", saved.get("stage").getAsString());
        assertFalse(saved.get("confirmed").getAsBoolean());
        assertThrows(IllegalArgumentException.class, () -> TiDBMixedAuxiliaryReplay
                .main(new String[] { "jdbc:missing-driver:", archive.toString(), archive.toString() }));
        assertEquals(original, Files.readString(archive));
    }
}
