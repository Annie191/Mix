package sqlancer.tidb.mixed;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.StateToReproduce;
import sqlancer.tidb.TiDBOptions;
import sqlancer.tidb.TiDBProvider;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;

/** Replays a trusted saved case without calling DeepSeek or regenerating inputs. */
public final class TiDBMixedReplay {
    private TiDBMixedReplay() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3) {
            throw new IllegalArgumentException("TiDBMixedReplay case.json [attempt-index] [runs|alternativeRuns]");
        }
        Path input = Path.of(args[0]);
        JsonObject saved = JsonParser.parseString(Files.readString(input)).getAsJsonObject();
        int attempt = args.length >= 2 ? Integer.parseInt(args[1]) : 0;
        JsonObject candidate = saved.getAsJsonArray("attempts").get(attempt).getAsJsonObject();
        String runSet = args.length == 3 ? args[2]
                : candidate.has("mismatchRunSet") ? candidate.get("mismatchRunSet").getAsString() : "runs";
        if (!List.of("runs", "alternativeRuns").contains(runSet) || !candidate.has("restoreStatements")
                || !candidate.has(runSet)) {
            throw new IllegalArgumentException("selected attempt has no materialized differential execution");
        }
        List<TiDBMixedSupport.Config> configs = new ArrayList<>();
        for (JsonElement item : candidate.getAsJsonArray(runSet)) {
            JsonObject map = item.getAsJsonObject().getAsJsonObject("config").getAsJsonObject("engines");
            Map<String, String> assignments = new LinkedHashMap<>();
            map.entrySet().forEach(e -> assignments.put(e.getKey(), e.getValue().getAsString()));
            configs.add(new TiDBMixedSupport.Config(assignments));
        }
        String host = System.getenv().getOrDefault("TIDB_HOST", TiDBOptions.DEFAULT_HOST);
        String port = System.getenv().getOrDefault("TIDB_PORT", "4000");
        String user = System.getenv().getOrDefault("TIDB_USER", "root");
        String password = System.getenv().getOrDefault("TIDB_PASSWORD", "");
        String database = "msreplay_" + UUID.randomUUID().toString().replace("-", "");
        Path output = input.resolveSibling("replay_" + database + ".json");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("source", input.toString());
        report.put("database", database);
        report.put("attempt", attempt);
        report.put("runSet", runSet);
        boolean passed = false;
        try (Connection connection = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/", user,
                password)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE " + TiDBMixedSupport.quoteIdentifier(database));
                statement.execute("USE " + TiDBMixedSupport.quoteIdentifier(database));
            }
            TiDBGlobalState state = new TiDBGlobalState();
            state.setConnection(new SQLConnection(connection));
            state.setDatabaseName(database);
            state.setMainOptions(new MainOptions());
            state.setDbmsSpecificOptions(new TiDBOptions());
            state.setRandomly(new Randomly(0));
            state.setState(new StateToReproduce(database, new TiDBProvider()));
            TiDBMixedSupport io = new TiDBMixedSupport(state);
            report.put("environment", io.environment);
            report.put("events", io.events);
            try {
                io.initialize();
                for (JsonElement sql : candidate.getAsJsonArray("restoreStatements")) {
                    io.exec(sql.getAsString());
                }
                state.updateSchema();
                io.replicas(TiDBMixedGeneration.baseTables(state));
                io.beginSnapshot();
                io.preflight(TiDBMixedGeneration.baseTables(state));
                List<TiDBMixedSupport.Run> runs = io.executeAll(candidate.get("sql").getAsString(), configs);
                TiDBMixedSupport.Outcome outcome = TiDBMixedSupport.compare(runs);
                report.put("runs", runs);
                report.put("outcome", outcome);
                passed = outcome.status.equals("PASS");
            } catch (Exception error) {
                report.put("error", error.toString());
            } finally {
                try {
                    io.reset();
                } catch (Exception error) {
                    report.put("cleanupError", error.toString());
                }
                TiDBMixedSupport.save(output, report);
            }
        }
        System.out.println(output);
        if (!passed) {
            throw new IllegalStateException("Replay did not PASS; inspect " + output);
        }
    }
}
