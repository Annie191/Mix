package sqlancer.tidb.mixed;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.oracle.TestOracle;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.TiDBSchema.TiDBTable;
import sqlancer.tidb.ast.TiDBExpression;
import sqlancer.tidb.mixed.TiDBDeepSeekAdvisor.Action;
import sqlancer.tidb.mixed.TiDBDeepSeekAdvisor.Advice;
import sqlancer.tidb.mixed.TiDBDeepSeekAdvisor.Profile;
import sqlancer.tidb.mixed.TiDBDeepSeekAdvisor.Stage;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;
import sqlancer.tidb.mixed.TiDBMixedSupport.Config;
import sqlancer.tidb.mixed.TiDBMixedSupport.Outcome;
import sqlancer.tidb.mixed.TiDBMixedSupport.Rejected;
import sqlancer.tidb.mixed.TiDBMixedSupport.Run;

/** Integration oracle; all SQL values/expressions originate in SQLancer or bounded policies. */
public final class TiDBAiMixedOracle implements TestOracle<TiDBGlobalState> {
    private final TiDBGlobalState state;
    private String lastQuery = "";
    private final Map<String, Long> history = new LinkedHashMap<>();

    public TiDBAiMixedOracle(TiDBGlobalState state) {
        this.state = state;
    }

    @Override
    public String getLastQueryString() {
        return lastQuery;
    }

    private List<String> names(Candidate candidate) {
        return candidate.tables.stream().map(TiDBTable::getName).collect(Collectors.toList());
    }

    static boolean confirmed(Outcome expected, List<Outcome> confirmations) {
        // Unrelated paths may fail or recover; every original differing pair must recur twice.
        return expected.mismatch() && confirmations.size() == 2 && confirmations.stream()
                .allMatch(actual -> actual.differingPairs.containsAll(expected.differingPairs));
    }

    private List<Run> replay(TiDBMixedSupport io, Candidate query, List<String> dump, List<Config> configs)
            throws Exception {
        io.reset();
        for (TiDBTable table : query.tables) {
            io.exec("DROP TABLE " + TiDBMixedSupport.quoteIdentifier(table.getName()));
        }
        for (String sql : dump) {
            io.exec(sql);
        }
        state.updateSchema();
        io.replicas(query.tables);
        io.beginSnapshot();
        io.preflight(query.tables);
        return io.executeAll(query.sql, configs);
    }

    private void confirm(TiDBMixedSupport io, TiDBDeepSeekAdvisor ai, Candidate query, List<String> dump,
            List<Config> configs, Outcome outcome, Map<String, Object> record) throws Exception {
        ai.suggest(Stage.DIAGNOSE,
                Map.of("sql", query.sql, "comparisons", outcome.comparisons, "failures", outcome.failures));
        List<List<Run>> confirmations = new ArrayList<>();
        List<Outcome> confirmationOutcomes = new ArrayList<>();
        record.put("confirmations", confirmations);
        record.put("confirmationTargets", outcome.differingPairs);
        record.put("confirmationOutcomes", confirmationOutcomes);
        record.put("confirmation", "UNCONFIRMED");
        for (int i = 0; i < 2; i++) {
            List<Run> repeated = replay(io, query, dump, configs);
            confirmations.add(repeated);
            confirmationOutcomes.add(TiDBMixedSupport.compare(repeated));
        }
        boolean stable = confirmed(outcome, confirmationOutcomes);
        record.put("confirmation", stable ? "STABLE_CANDIDATE" : "UNCONFIRMED");
        Advice reduction = ai.suggest(Stage.REDUCE,
                Map.of("sql", query.sql, "hasWhere", query.ast.getWhereClause() != null, "stable", stable));
        boolean tryReduction = reduction.action == Action.REDUCE_WHERE
                || Boolean.parseBoolean(System.getenv().getOrDefault("MS_REDUCE_WHERE", "false"));
        if (stable && tryReduction && query.ast.getWhereClause() != null) {
            TiDBExpression old = query.ast.getWhereClause();
            Candidate reduced;
            try {
                query.ast.setWhereClause(null);
                reduced = new Candidate(query.ast, query.tables, query.guidedColumns);
            } finally {
                query.ast.setWhereClause(old);
            }
            List<Run> first = replay(io, reduced, dump, configs);
            List<Run> second = replay(io, reduced, dump, configs);
            List<Outcome> reducedOutcomes = List.of(TiDBMixedSupport.compare(first), TiDBMixedSupport.compare(second));
            record.put("reduction", Map.of("sql", reduced.sql, "runs", List.of(first, second), "outcomes",
                    reducedOutcomes, "accepted",
                    reducedOutcomes.stream().allMatch(actual -> outcome.signature().equals(actual.signature()))));
        }
    }

    @Override
    public void check() throws Exception {
        TiDBMixedSupport io = new TiDBMixedSupport(state);
        TiDBDeepSeekAdvisor ai = TiDBDeepSeekAdvisor.fromEnvironment();
        Path directory = Path.of(System.getenv().getOrDefault("MS_ARTIFACT_DIR", "logs/mixed"),
                state.getDatabaseName() + "_" + UUID.randomUUID());
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("target", TiDBMixedTarget.VERSION);
        report.put("tidbCommit", TiDBMixedTarget.COMMIT);
        report.put("sourceCommit", TiDBMixedTarget.SQLANCER_BASE);
        report.put("seed", state.getRandomly().getSeed());
        report.put("database", state.getDatabaseName());
        report.put("environment", io.environment);
        report.put("events", io.events);
        report.put("ai", ai.audit);
        report.put("priorHistory", new LinkedHashMap<>(history));
        List<Map<String, Object>> attempts = new ArrayList<>();
        report.put("attempts", attempts);
        boolean mismatch = false;
        boolean passed = false;
        boolean cleanupFailed = false;
        try {
            io.initialize();
            for (int revision = 0; revision < 3; revision++) {
                io.reset();
                // New revision: old results remain in attempts and are never relabeled PASS.
                Map<String, Object> record = new LinkedHashMap<>();
                attempts.add(record);
                record.put("revision", revision);
                List<Map<String, Object>> schema = new ArrayList<>();
                for (TiDBTable table : TiDBMixedGeneration.baseTables(state)) {
                    schema.add(Map.of("table", table.getName(), "columns", table.getColumns().stream()
                            .map(c -> c.getName() + ":" + c.getType()).collect(Collectors.toList())));
                }
                Advice preparation = ai.suggest(Stage.PREPARE, Map.of("schema", schema, "history", history));
                boolean guided = preparation.action == Action.GUIDE_JOIN || Randomly.getBoolean();
                record.put("guided", guided);
                record.put("schemaBeforeRepair", schema);
                Candidate query;
                try {
                    query = TiDBMixedGeneration.query(state, guided, io);
                } catch (IgnoreMeException ignored) {
                    record.put("status", "GENERATION_REJECTED");
                    continue;
                }
                lastQuery = query.sql;
                record.put("sql", query.sql);
                record.put("guidedColumns", query.guidedColumns);
                // Precheck before INSERT. Unknown SQL errors are recorded, not added to expected errors by AI.
                try {
                    io.textRows("EXPLAIN FORMAT='brief' " + query.sql);
                } catch (java.sql.SQLException | Rejected error) {
                    record.put("status", "PRECHECK_REJECTED");
                    record.put("error", error.toString());
                    continue;
                }
                Advice dataAdvice = ai.suggest(Stage.DATA,
                        Map.of("sql", query.sql, "schema", schema, "guidedColumns", query.guidedColumns));
                Profile profile = dataAdvice.action == Action.SET_PROFILE ? dataAdvice.profile
                        : Randomly.fromOptions(Profile.values());
                record.put("dataGeneration", TiDBMixedGeneration.fill(state, query, profile, io));
                List<String> dump = io.dump(query.tables);
                record.put("restoreStatements", dump);
                io.replicas(query.tables);
                io.beginSnapshot();
                record.put("snapshot", io.snapshot);
                io.preflight(query.tables);
                List<Config> configs = TiDBMixedSupport.configurations(names(query));
                List<Run> runs = io.executeAll(query.sql, configs);
                Outcome outcome = TiDBMixedSupport.compare(runs);
                record.put("runs", runs);
                record.put("outcome", outcome);
                report.put("status", outcome.status);
                history.merge(outcome.status, 1L, Long::sum);
                TiDBMixedSupport.save(directory.resolve("case.json"), report); // Save before any confirmation/mutation.
                if (outcome.mismatch()) {
                    mismatch = true;
                    record.put("mismatchRunSet", "runs");
                    TiDBMixedSupport.save(directory.resolve("case.json"), report);
                    confirm(io, ai, query, dump, configs, outcome, record);
                    break; // A discrepancy is never repaired away or overwritten by another candidate.
                }
                if (outcome.status.equals("INVALID_CONFIGURATION")) {
                    Advice repair = ai.suggest(Stage.INVALID_PLAN, Map.of("sql", query.sql, "failures",
                            outcome.failures, "plans", runs.stream().map(r -> r.plan).collect(Collectors.toList())));
                    if (repair.action == Action.TRY_ASSIGNMENTS) {
                        Set<String> previous = configs.stream().map(c -> c.name).collect(Collectors.toSet());
                        List<Config> alternatives = TiDBMixedSupport.configurations(names(query));
                        if (alternatives.stream().allMatch(c -> previous.contains(c.name))) {
                            record.put("alternativeStatus", "NO_NEW_ASSIGNMENT");
                            continue;
                        }
                        List<Run> alternate = io.executeAll(query.sql, alternatives);
                        Outcome alternativeOutcome = TiDBMixedSupport.compare(alternate);
                        record.put("alternativeRuns", alternate);
                        record.put("alternativeOutcome", alternativeOutcome);
                        mismatch = alternativeOutcome.mismatch();
                        if (mismatch) {
                            record.put("mismatchRunSet", "alternativeRuns");
                            report.put("status", alternativeOutcome.status);
                            TiDBMixedSupport.save(directory.resolve("case.json"), report);
                            confirm(io, ai, query, dump, alternatives, alternativeOutcome, record);
                            break;
                        }
                        passed = alternativeOutcome.status.equals("PASS");
                        if (passed) {
                            report.put("status", "PASS");
                            break;
                        }
                    }
                    continue; // Rule fallback or REGENERATE_QUERY; creates a new version of S/Q/D.
                }
                passed = outcome.status.equals("PASS");
                break;
            }
        } catch (Exception error) {
            report.put("error", error.toString());
            if (!mismatch) {
                report.put("status", error instanceof Rejected ? ((Rejected) error).status : "EXECUTION_ERROR");
            }
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            ai.suggest(Stage.DIAGNOSE, Map.of("error", error.toString(), "sql", lastQuery));
        } finally {
            try {
                io.reset();
            } catch (Exception cleanup) {
                report.put("cleanupError", cleanup.toString());
                cleanupFailed = true;
            }
            report.putIfAbsent("status", "GENERATION_REJECTED");
            TiDBMixedSupport.save(directory.resolve("case.json"), report);
            TiDBMixedSupport.save(directory.resolve("history.json"), history);
        }
        if (mismatch) {
            throw new AssertionError("Mixed-storage discrepancy; inspect " + directory);
        }
        if (cleanupFailed) {
            throw new java.sql.SQLException("Mixed-storage session cleanup failed; inspect " + directory);
        }
        if (!passed) {
            throw new IgnoreMeException();
        }
    }
}
