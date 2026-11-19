package sqlancer.tidb.mixed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Replays archived statements in a new database, never overwriting the supplied connection's database. */
public final class TiDBMixedAuxiliaryReplay {
    private TiDBMixedAuxiliaryReplay() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: TiDBMixedAuxiliaryReplay <jdbc-url> <error.json>; "
                    + "credentials from MS_REPLAY_USER (default root) and MS_REPLAY_PASSWORD");
        }
        JsonObject record = JsonParser.parseString(Files.readString(Path.of(args[1]))).getAsJsonObject();
        if (!"CAPTURED".equals(record.get("replay_status").getAsString())) {
            throw new IllegalArgumentException("Archive has no complete restore evidence");
        }
        String database = "mixed_aux_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(args[0],
                System.getenv().getOrDefault("MS_REPLAY_USER", "root"),
                System.getenv().getOrDefault("MS_REPLAY_PASSWORD", ""));
                Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(30);
            statement.execute("CREATE DATABASE " + TiDBMixedSupport.quoteIdentifier(database));
            statement.execute("USE " + TiDBMixedSupport.quoteIdentifier(database));
            System.out.println("Replay database: " + database);
            statement.execute("SET SESSION tidb_isolation_read_engines='tikv'");
            for (JsonElement sql : record.getAsJsonArray("restoreStatements")) {
                statement.execute(sql.getAsString());
            }
            JsonObject session = record.getAsJsonArray("session").get(0).getAsJsonObject();
            for (String key : session.keySet()) {
                statement.execute("SET SESSION " + TiDBMixedSupport.quoteIdentifier(key) + "="
                        + TiDBMixedSupport.literal(session.get(key).getAsString()));
            }
            // The original TSO describes the old database. Recreated data must use a fresh read.
            try (PreparedStatement query = connection.prepareStatement(record.get("sql").getAsString())) {
                query.setQueryTimeout(30);
                query.setMaxRows(1);
                TiDBMixedReadOnly.bind(query, record.getAsJsonArray("bindings"));
                try (ResultSet rows = query.executeQuery()) {
                    System.out.println("SQL succeeded; has row: " + rows.next());
                }
            }
        }
    }
}
