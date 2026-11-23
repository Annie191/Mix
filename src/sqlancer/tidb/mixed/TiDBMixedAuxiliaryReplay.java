package sqlancer.tidb.mixed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Replays archived statements in a new database, never overwriting the supplied connection's database. */
public final class TiDBMixedAuxiliaryReplay {
    private TiDBMixedAuxiliaryReplay() {
    }

    static Map<String, Object> executeObserved(Connection connection, JsonObject record) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("confirmed", false);
        result.put("expected_error_signature", TiDBMixedAuxiliaryErrors.signature(record));
        try (PreparedStatement query = connection.prepareStatement(record.get("sql").getAsString())) {
            query.setQueryTimeout(30);
            query.setMaxRows(1);
            TiDBMixedReadOnly.bind(query, record.getAsJsonArray("bindings"));
            try (ResultSet rows = query.executeQuery()) {
                result.put("has_row", rows.next());
                result.put("status", "NOT_REPRODUCED");
            }
        } catch (SQLException error) {
            result.put("error", error.toString());
            result.put("sqlstate", error.getSQLState());
            result.put("error_code", error.getErrorCode());
            result.put("exception_chain", TiDBMixedAuxiliaryErrors.exceptionChain(error));
            JsonObject actual = record.deepCopy();
            actual.add("exception_chain", new Gson().toJsonTree(TiDBMixedAuxiliaryErrors.exceptionChain(error)));
            String signature = TiDBMixedAuxiliaryErrors.signature(actual);
            result.put("actual_error_signature", signature);
            boolean same = signature.equals(result.get("expected_error_signature"));
            result.put("same_error_signature", same);
            result.put("status", same ? "REPRODUCED_ERROR_SIGNATURE" : "DIFFERENT_ERROR");
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException("Usage: TiDBMixedAuxiliaryReplay <jdbc-url> <error.json> [result.json]; "
                    + "credentials from MS_REPLAY_USER (default root) and MS_REPLAY_PASSWORD");
        }
        JsonObject record = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        if (!"CAPTURED".equals(record.get("replay_status").getAsString())) {
            throw new IllegalArgumentException("Archive has no complete restore evidence");
        }
        String database = "mixed_aux_" + UUID.randomUUID().toString().replace("-", "");
        Path archive = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = args.length == 3 ? Path.of(args[2])
                : archive.resolveSibling(archive.getFileName() + "-replay-" + UUID.randomUUID() + ".json");
        if (output.toAbsolutePath().normalize().equals(archive)
                || Files.exists(output) && Files.isSameFile(archive, output)) {
            throw new IllegalArgumentException("Replay result must not overwrite the original archive");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("archive", archive.toString());
        result.put("database", database);
        result.put("started_at", Instant.now().toString());
        result.put("confirmed", false);
        result.put("stage", "CONNECT");
        Exception failure = null;
        try (Connection connection = DriverManager.getConnection(args[0],
                System.getenv().getOrDefault("MS_REPLAY_USER", "root"),
                System.getenv().getOrDefault("MS_REPLAY_PASSWORD", ""));
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            result.put("stage", "CREATE_DATABASE");
            statement.execute("CREATE DATABASE " + TiDBMixedSupport.quoteIdentifier(database));
            statement.execute("USE " + TiDBMixedSupport.quoteIdentifier(database));
            System.out.println("Replay database: " + database);
            statement.execute("SET SESSION tidb_isolation_read_engines='tikv'");
            try (ResultSet version = statement.executeQuery("SELECT TIDB_VERSION()")) {
                if (version.next()) {
                    result.put("server_version", version.getString(1));
                }
            }
            result.put("stage", "RESTORE_DATA");
            for (JsonElement sql : record.getAsJsonArray("restoreStatements")) {
                statement.execute(sql.getAsString());
            }
            JsonObject session = record.getAsJsonArray("session").get(0).getAsJsonObject();
            result.put("stage", "RESTORE_SESSION");
            for (String key : session.keySet()) {
                statement.execute("SET SESSION " + TiDBMixedSupport.quoteIdentifier(key) + "="
                        + TiDBMixedSupport.literal(session.get(key).getAsString()));
            }
            // The original TSO describes the old database. Recreated data must use a fresh read.
            result.put("stage", "EXECUTE_QUERY");
            result.putAll(executeObserved(connection, record));
        } catch (SQLException | RuntimeException error) {
            result.put("status", "REPLAY_SETUP_FAILED");
            result.put("setup_error", error.toString());
            failure = error;
        } finally {
            result.put("finished_at", Instant.now().toString());
            TiDBMixedSupport.save(output, result);
            System.out.println("Replay result: " + output + "; status=" + result.get("status"));
        }
        if (failure != null) {
            throw failure;
        }
    }
}
