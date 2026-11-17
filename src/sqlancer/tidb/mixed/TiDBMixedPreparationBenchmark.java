package sqlancer.tidb.mixed;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.StateToReproduce;
import sqlancer.tidb.TiDBExpressionGenerator;
import sqlancer.tidb.TiDBOptions;
import sqlancer.tidb.TiDBProvider;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.TiDBSchema.TiDBColumn;
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
import sqlancer.tidb.mixed.TiDBDeepSeekAdvisor.Profile;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;
import sqlancer.tidb.mixed.TiDBMixedPreparationHarness.Requirement;
import sqlancer.tidb.mixed.TiDBMixedSupport.Config;
import sqlancer.tidb.mixed.TiDBMixedSupport.Run;

/** Paired, bounded controlled corpus. Uses real TiKV/TiFlash; no synthetic AI measurements. */
public final class TiDBMixedPreparationBenchmark {
    private TiDBMixedPreparationBenchmark() {
    }

    static Candidate candidate(List<TiDBTable> tables, long seed) {
        TiDBColumn leftKey = tables.get(0).getColumns().get(0);
        TiDBColumn rightKey = tables.get(1).getColumns().get(0);
        TiDBExpression left = new TiDBColumnReference(leftKey);
        TiDBExpression right = new TiDBColumnReference(rightKey);
        TiDBSelect ast = new TiDBSelect();
        ast.setFetchColumns(List.of(left, right));
        ast.setFromList(List.of());
        ast.setJoinList(List.of(new TiDBJoin(new TiDBTableReference(tables.get(0)),
                new TiDBTableReference(tables.get(1)), seed % 6 == 1 ? TiDBJoin.JoinType.LEFT : TiDBJoin.JoinType.INNER,
                new TiDBBinaryComparisonOperation(left, right, TiDBComparisonOperator.EQUALS))));
        ast.setWhereClause(
                seed % 6 == 5 ? TiDBConstant.createBooleanConstant(false) : new TiDBBinaryComparisonOperation(left,
                        TiDBConstant.createIntConstant(10), TiDBComparisonOperator.GREATER));
        if (seed % 6 == 4) {
            ast.setGroupByExpressions(List.of(left));
            ast.setFetchColumns(List.of(left,
                    new TiDBAggregate(List.of(TiDBConstant.createIntConstant(1)), TiDBAggregateFunction.COUNT)));
        }
        return new Candidate(ast, tables, List.of(leftKey.getFullQualifiedName(), rightKey.getFullQualifiedName()));
    }

    static List<Requirement> goals(long seed) {
        return seed % 6 == 5 ? List.of(Requirement.empty(true), Requirement.count("j0_pairs_after", 0, false)) : List
                .of(Requirement.count("j0_pairs_after", 2, true), Requirement.count("j0_duplicate_after", 1, true));
    }

    static JsonObject packet(TiDBMixedObservations facts, JsonObject schema, String caseId) {
        JsonObject budget = TiDBMixedPreparationHarness.preparationBudget();
        return facts.statePacket("prepare", caseId, schema, new JsonArray(), budget);
    }

    static JsonObject decision(JsonObject packet, String action, JsonArray operations) {
        JsonObject plan = new JsonObject();
        for (String key : List.of("protocol_version", "request_id", "case_id", "query_id", "schema_revision",
                "data_revision")) {
            plan.add(key, packet.get(key));
        }
        plan.addProperty("decision", action);
        plan.addProperty("reason", "Fixed-budget baseline: reuse SQLancer RANDOM/SHARED value policy");
        plan.addProperty("goal", "Evaluate data scenarios using independent exact measurements");
        plan.addProperty("expected_effect", "Unknown until measured; no nonempty-result requirement");
        for (String key : List.of("evidence_ids", "observation_requests", "verification_requests",
                "structured_goals")) {
            plan.add(key, new JsonArray());
        }
        plan.add("operations", operations);
        plan.add("verification_limit", action.equals("append_rows")
                ? new com.google.gson.JsonPrimitive("Benchmark applies an independently budgeted verification floor")
                : JsonNull.INSTANCE);
        plan.add("stop_reason", action.equals("stop") ? new com.google.gson.JsonPrimitive("Fixed row budget finished")
                : JsonNull.INSTANCE);
        return plan;
    }

    private static JsonObject cell(TiDBExpression expression) {
        JsonObject value = new JsonObject();
        if (expression instanceof TiDBConstant.TiDBNullConstant) {
            value.addProperty("type", "null");
            value.add("value", JsonNull.INSTANCE);
        } else {
            value.addProperty("type", "integer");
            value.addProperty("value", Long.toString(((TiDBConstant.TiDBIntConstant) expression).getValue()));
        }
        return value;
    }

    static JsonArray baseline(TiDBGlobalState state, Candidate query, Profile profile, long rowBudget) {
        TiDBMixedPreparationHarness.preparationBudget(rowBudget);
        List<TiDBExpression> pool = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pool.add(TiDBConstant.createIntConstant(state.getRandomly().getInteger(0, 100)));
        }
        BiFunction<TiDBColumn, TiDBExpression, TiDBExpression> policy = TiDBMixedGeneration.valuePolicy(query, profile,
                pool);
        TiDBExpressionGenerator generator = new TiDBExpressionGenerator(state);
        JsonArray operations = new JsonArray();
        for (int tableIndex = 0; tableIndex < query.tables.size(); tableIndex++) {
            TiDBTable table = query.tables.get(tableIndex);
            long tableRows = rowBudget / query.tables.size() + (tableIndex < rowBudget % query.tables.size() ? 1 : 0);
            if (tableRows == 0) {
                continue;
            }
            JsonObject operation = new JsonObject();
            operation.addProperty("table", table.getName());
            JsonArray rows = new JsonArray();
            for (int i = 0; i < tableRows; i++) {
                JsonObject row = new JsonObject();
                for (TiDBColumn column : table.getColumns()) {
                    row.add(column.getName(), cell(
                            policy.apply(column, generator.generateConstant(column.getType().getPrimitiveDataType()))));
                }
                rows.add(row);
            }
            operation.add("rows", rows);
            operations.add(operation);
        }
        return operations;
    }

    private static TiDBGlobalState state(Connection connection, String database, long seed) {
        TiDBGlobalState state = new TiDBGlobalState();
        state.setConnection(new SQLConnection(connection));
        state.setDatabaseName(database);
        state.setMainOptions(new MainOptions());
        state.setDbmsSpecificOptions(new TiDBOptions());
        state.setRandomly(new Randomly(seed));
        state.setState(new StateToReproduce(database, new TiDBProvider()));
        return state;
    }

    private static JsonObject measure(TiDBMixedSupport io, Candidate query, JsonObject schema, long revision,
            String caseId) throws Exception {
        return measure(io, query, schema, revision, caseId, new JsonArray());
    }

    private static JsonObject measure(TiDBMixedSupport io, Candidate query, JsonObject schema, long revision,
            String caseId, JsonArray goals) throws Exception {
        TiDBMixedObservations facts = new TiDBMixedObservations(query, "paired_query", 0, revision);
        facts.collectRequests(io, facts.assessmentRequests(goals), new TiDBMixedObservations.Budget(64, 64, 30_000));
        return packet(facts, schema, caseId);
    }

    private static void schema(TiDBMixedSupport io, long seed) throws Exception {
        if (io.textRows("SHOW TABLES").isEmpty()) {
            io.exec("CREATE TABLE t0(k BIGINT NULL" + (seed % 6 == 2 ? " UNIQUE" : "") + ",v BIGINT NULL)");
            io.exec("CREATE TABLE t1(k BIGINT NULL,v BIGINT NULL)");
        }
        io.exec("DELETE FROM t0");
        io.exec("DELETE FROM t1");
        io.exec("INSERT INTO t0 VALUES (5,5)");
        io.exec("INSERT INTO t1 VALUES (5,5)");
        if (seed % 6 == 3) {
            io.exec("INSERT INTO t0 VALUES(12,1),(NULL,1)");
            io.exec("INSERT INTO t1 VALUES(12,1),(12,2)");
        }
    }

    private static JsonObject runArm(Connection connection, Path output, long seed, String arm, boolean engines,
            String database) throws Exception {
        TiDBGlobalState state = state(connection, database, seed);
        TiDBMixedSupport io = new TiDBMixedSupport(state);
        JsonObject report = new JsonObject();
        report.addProperty("seed", seed);
        report.addProperty("strategy", arm);
        report.addProperty("database", database);
        report.addProperty("status", "STARTED");
        report.addProperty("engines_requested", engines);
        long started = System.nanoTime();
        Path file = output.resolve("seed_" + seed + "_" + arm + ".json");
        TiDBDeepSeekPreparationClient client = null;
        try {
            io.exec("USE " + TiDBMixedSupport.quoteIdentifier(database));
            io.initialize();
            schema(io, seed);
            state.updateSchema();
            Candidate query = candidate(TiDBMixedGeneration.baseTables(state), seed);
            if (engines) {
                for (TiDBTable table : query.tables) {
                    io.exec("ALTER TABLE " + TiDBMixedSupport.quoteIdentifier(table.getName())
                            + " SET TIFLASH REPLICA 1");
                }
            }
            report.addProperty("sql", query.sql);
            JsonObject actual = TiDBMixedMetadata.collect(io, database, query.tables, 0).schema;
            JsonObject initial = measure(io, query, actual, 0, "paired_" + seed);
            report.add("initial_state", initial.deepCopy());
            long preparationStarted = System.nanoTime();
            // Full before/after measurements are assessment evidence, never model input.
            TiDBMixedObservations bootstrap = new TiDBMixedObservations(query, "paired_query", 0, 0);
            JsonObject preparationBudget = TiDBMixedPreparationHarness.preparationBudget();
            bootstrap.bootstrap(io, preparationBudget);
            JsonObject modelInitial = bootstrap.statePacket("prepare", "paired_" + seed, actual, new JsonArray(),
                    preparationBudget);
            report.add("model_initial_state", modelInitial.deepCopy());
            report.add("bootstrap_audit", new Gson().toJsonTree(bootstrap.audit));
            JsonObject before = TiDBMixedDataCoverage.snapshot(initial);
            report.add("data_coverage_before", before);
            List<Config> configs = TiDBMixedSupport.configurations(List.of("t0", "t1"));
            report.add("selected_engine_configurations", new Gson().toJsonTree(configs));
            state.setRandomly(new Randomly(seed));
            TiDBMixedPreparationHarness harness = new TiDBMixedPreparationHarness(state, query, modelInitial,
                    goals(seed), 100, 64, 2);
            harness.verificationFloor(
                    TiDBMixedDataCoverage.measurementIds(new TiDBMixedObservations(query, "paired_query", 0, 0)));
            JsonObject preparation;
            if (arm.equals("AI")) {
                client = TiDBDeepSeekPreparationClient.fromEnvironment(3);
                preparation = harness.run(client);
            } else {
                JsonArray rows = baseline(state, query, Profile.valueOf(arm),
                        initial.getAsJsonObject("remaining_budget").get("insert_rows").getAsLong());
                AtomicBoolean first = new AtomicBoolean(true);
                preparation = harness.run(messages -> {
                    JsonObject current = JsonParser.parseString(messages.get(2).get("content")).getAsJsonObject();
                    boolean append = first.getAndSet(false) && !rows.isEmpty();
                    return decision(current, append ? "append_rows" : "stop", append ? rows : new JsonArray())
                            .toString();
                });
            }
            report.addProperty("preparation_elapsed_ms", (System.nanoTime() - preparationStarted) / 1_000_000);
            report.add("preparation", preparation);
            if (preparation.get("requires_rebuild").getAsBoolean()) {
                throw new IllegalStateException("uncertain append outcome");
            }
            long revision = preparation.getAsJsonObject("final_state").get("data_revision").getAsLong();
            JsonObject finalState = measure(io, query, actual, revision, "paired_" + seed,
                    preparation.getAsJsonArray("model_goals"));
            report.add("final_state", finalState);
            report.add("final_model_goal_evaluation",
                    TiDBMixedPreparationGoals.evaluate(finalState, preparation.getAsJsonArray("model_goals"), false));
            JsonObject after = TiDBMixedDataCoverage.snapshot(finalState);
            report.add("data_coverage_after", after);
            report.add("data_coverage_change", TiDBMixedDataCoverage.difference(before, after));
            report.add("final_goal_evaluation", TiDBMixedPreparationHarness.evaluate(finalState, goals(seed)));
            report.add("restore_statements", new Gson().toJsonTree(io.dump(query.tables)));
            TiDBMixedSupport.save(file, report);
            if (engines) {
                long engineStarted = System.nanoTime();
                io.replicas(query.tables);
                io.beginSnapshot();
                try {
                    io.preflight(query.tables);
                    report.addProperty("preflight_status", "PASS");
                    List<Run> runs = io.executeAll(query.sql, configs);
                    report.add("engine_runs", new Gson().toJsonTree(runs));
                    report.add("engine_outcome", new Gson().toJsonTree(TiDBMixedSupport.compare(runs)));
                    report.add("engine_coverage",
                            new Gson().toJsonTree(new TiDBMixedCoverage().record(query.ast, runs)));
                } catch (TiDBMixedSupport.Rejected failure) {
                    report.addProperty("preflight_status", failure.status);
                    throw failure;
                } finally {
                    report.addProperty("engine_elapsed_ms", (System.nanoTime() - engineStarted) / 1_000_000);
                }
            }
            report.addProperty("status", "COMPLETED");
        } catch (Exception error) {
            report.addProperty("status", "ERROR");
            report.addProperty("error", error.toString());
        } finally {
            report.add("environment", new Gson().toJsonTree(io.environment));
            report.add("events", new Gson().toJsonTree(io.events));
            report.add("api_audit", client == null ? new JsonArray() : client.audit.deepCopy());
            report.addProperty("elapsed_ms", (System.nanoTime() - started) / 1_000_000);
            try {
                io.reset();
            } catch (Exception error) {
                report.addProperty("cleanup_error", error.toString());
            }
            TiDBMixedSupport.save(file, report);
        }
        System.out.println("seed=" + seed + " strategy=" + arm + " status=" + report.get("status").getAsString());
        return report;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 4) {
            throw new IllegalArgumentException(
                    "Benchmark output-dir comma-separated-seeds [RANDOM,SHARED,AI] [engines=true|false]");
        }
        Path output = Path.of(args[0]);
        if (java.nio.file.Files.exists(output.resolve("all.json"))) {
            throw new IllegalArgumentException("use a new output directory; preserve earlier experiments");
        }
        String[] arms = args.length > 2 ? args[2].split(",") : new String[] { "RANDOM", "SHARED", "AI" };
        boolean engines = args.length < 4 || Boolean.parseBoolean(args[3]);
        for (String arm : arms) {
            if (!List.of("RANDOM", "SHARED", "AI").contains(arm)) {
                throw new IllegalArgumentException("unknown strategy");
            }
        }
        if (java.util.Arrays.stream(arms).distinct().count() != arms.length
                || java.util.Arrays.stream(args[1].split(",")).distinct().count() != args[1].split(",").length) {
            throw new IllegalArgumentException("duplicate strategy or seed");
        }
        for (String seed : args[1].split(",")) {
            Long.parseLong(seed);
        }
        TiDBMixedPreparationHarness.preparationBudget(); // Validate row configuration before creating any database.
        JsonArray reports = new JsonArray();
        try (Connection connection = DriverManager.getConnection(
                "jdbc:mysql://" + System.getenv().getOrDefault("TIDB_HOST", TiDBOptions.DEFAULT_HOST) + ":"
                        + System.getenv().getOrDefault("TIDB_PORT", "22000") + "/",
                System.getenv().getOrDefault("TIDB_USER", "root"), System.getenv().getOrDefault("TIDB_PASSWORD", ""))) {
            for (String text : args[1].split(",")) {
                long seed = Long.parseLong(text);
                String database = "msbench_" + UUID.randomUUID().toString().replace("-", "");
                try {
                    try (java.sql.Statement setup = connection.createStatement()) {
                        setup.execute("CREATE DATABASE " + TiDBMixedSupport.quoteIdentifier(database));
                    }
                    // Reuse table IDs/replicas; DELETE restores identical initial rows between arms.
                    // Rotate order to reduce systematic warmup bias. Replication wait is reported separately.
                    for (int i = 0; i < arms.length; i++) {
                        reports.add(runArm(connection, output, seed,
                                arms[(i + Math.floorMod(seed, arms.length)) % arms.length], engines, database));
                        TiDBMixedSupport.save(output.resolve("all.json"), reports);
                        TiDBMixedSupport.save(output.resolve("summary.json"),
                                TiDBMixedBenchmarkMetrics.summarize(reports));
                    }
                } finally {
                    try (java.sql.Statement cleanup = connection.createStatement()) {
                        cleanup.execute("SET SESSION tidb_snapshot=''");
                        cleanup.execute("DROP DATABASE IF EXISTS " + TiDBMixedSupport.quoteIdentifier(database));
                    }
                }
            }
        }
    }
}
