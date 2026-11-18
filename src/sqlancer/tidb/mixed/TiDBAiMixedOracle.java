package sqlancer.tidb.mixed;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
    private final Map<String, Long> preflightCounts = new LinkedHashMap<>(
            Map.of("PASS", 0L, "DATA_PREFLIGHT_MISMATCH", 0L, "PREFLIGHT_FAILED", 0L));
    private final TiDBMixedCoverage coverage = new TiDBMixedCoverage();

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

    Map<String, Long> preflightCounts() {
        return new LinkedHashMap<>(preflightCounts);
    }

    private void countedPreflight(TiDBMixedSupport io, List<TiDBTable> tables) {
        try {
            io.preflight(tables);
            preflightCounts.merge("PASS", 1L, Long::sum);
        } catch (Rejected error) {
            String status = error.status.equals("DATA_PREFLIGHT_MISMATCH") ? error.status : "PREFLIGHT_FAILED";
            preflightCounts.merge(status, 1L, Long::sum);
            throw error;
        }
    }

    boolean preflightReady(TiDBMixedSupport io, List<TiDBTable> tables, Map<String, Object> record) {
        try {
            countedPreflight(io, tables);
            record.put("preflightStatus", "PASS");
            return true;
        } catch (Rejected error) {
            record.put("preflightStatus", error.status);
            if (!error.status.equals("DATA_PREFLIGHT_MISMATCH")) {
                throw error;
            }
            record.put("status", error.status);
            record.put("error", error.getMessage());
            return false;
        }
    }

    List<Run> executeWithCoverage(TiDBMixedSupport io, Candidate query, List<Config> configs,
            Map<String, Object> record, String coverageKey) {
        List<Run> runs = io.executeAll(query.sql, configs);
        record.put(coverageKey, coverage.record(query.ast, runs));
        return runs;
    }

    static JsonObject prepareData(TiDBMixedPreparationHarness preparationLoop,
            TiDBMixedPreparationHarness.ClientFactory factory, Map<String, Object> record) throws Exception {
        record.put("preparationApiAudit", preparationLoop.apiAudit);
        JsonObject prepared = preparationLoop.runWithClient(factory);
        record.put("dataPreparation", prepared);
        requireUsablePreparation(prepared);
        return prepared;
    }

    static void requireUsablePreparation(JsonObject prepared) {
        if (prepared.get("requires_rebuild").getAsBoolean()) {
            throw new Rejected("PREPARATION_UNCERTAIN", "append outcome requires rebuilding data");
        }
    }

    static boolean saveEvidence(Path file, Object value, List<Map<String, String>> failures) {
        try {
            TiDBMixedSupport.save(file, value);
            return true;
        } catch (java.io.IOException | RuntimeException error) {
            failures.add(Map.of("target", file.toString(), "error", error.toString()));
            return false;
        }
    }

    private static void saveRequired(Path file, Object value, List<Map<String, String>> failures) {
        if (!saveEvidence(file, value, failures)) {
            throw new Rejected("EVIDENCE_SAVE_ERROR", "cannot save original evidence before confirmation: " + file);
        }
    }

    static boolean saveFinalEvidence(Path directory, Map<String, Object> report, Map<String, Long> history,
            Map<String, Long> preflightCounts, Object coverage, boolean preflightMismatch, boolean mismatch,
            List<Map<String, String>> failures) {
        boolean saved = true;
        if (preflightMismatch) {
            saved &= saveEvidence(directory.resolve("preflight-mismatch.json"), report, failures);
        }
        saved &= saveEvidence(directory.resolve("history.json"), history, failures);
        saved &= saveEvidence(directory.resolve("preflight-counts.json"), preflightCounts, failures);
        saved &= saveEvidence(directory.resolve("coverage.json"), coverage, failures);
        if (!saved && !mismatch) {
            report.put("status", "EVIDENCE_SAVE_ERROR");
        }
        // Write the case last so auxiliary failures cannot leave it labelled PASS.
        saved &= saveEvidence(directory.resolve("case.json"), report, failures);
        if (!saved && !mismatch) {
            report.put("status", "EVIDENCE_SAVE_ERROR");
        }
        return saved;
    }

    static JsonObject assessData(TiDBMixedSupport io, Candidate query, JsonObject modelInitial, long dataRevision,
            JsonArray goals, TiDBMixedObservations.Budget budget, Map<String, Object> record, String key)
            throws Exception {
        long started = System.nanoTime();
        TiDBMixedObservations facts = new TiDBMixedObservations(query, modelInitial.get("query_id").getAsString(),
                modelInitial.get("schema_revision").getAsLong(), dataRevision);
        Map<String, Object> assessment = new LinkedHashMap<>();
        record.put(key, assessment);
        assessment.put("status", "STARTED");
        assessment.put("budget",
                Map.of("probe_requests", budget.probes, "rows_per_probe", budget.rows, "time_ms", budget.timeMs));
        assessment.put("dataRevision", dataRevision);
        assessment.put("queryId", modelInitial.get("query_id").getAsString());
        assessment.put("schemaRevision", modelInitial.get("schema_revision").getAsLong());
        // Give the same static coverage baseline priority on both sides. Model diagnostics use the remainder.
        Map<String, com.google.gson.JsonElement> requests = new LinkedHashMap<>();
        for (String id : TiDBMixedDataCoverage.measurementIds(facts)) {
            requests.put(TiDBMixedObservationRequest.key(id, null), new com.google.gson.JsonPrimitive(id));
        }
        for (com.google.gson.JsonElement request : facts.assessmentRequests(goals)) {
            requests.putIfAbsent(TiDBMixedObservationRequest.key(request), request);
        }
        JsonArray selected = new JsonArray();
        requests.values().forEach(selected::add);
        assessment.put("requests", selected);
        try {
            facts.collectRequests(io, selected, budget);
            assessment.put("status", "COLLECTED");
            JsonObject packet = facts.statePacket(key, modelInitial.get("case_id").getAsString(),
                    modelInitial.getAsJsonObject("schema"), new JsonArray(),
                    modelInitial.getAsJsonObject("remaining_budget").deepCopy());
            assessment.put("coverage", TiDBMixedDataCoverage.snapshot(packet));
            return packet;
        } catch (Exception error) {
            assessment.put("status", "ERROR");
            assessment.put("error", error.toString());
            throw error;
        } finally {
            long elapsed = (System.nanoTime() - started + 999_999) / 1_000_000;
            assessment.put("elapsed_ms", elapsed);
            assessment.put("time_remaining_ms", Math.max(0, budget.timeMs - elapsed));
            assessment.put("snapshot", facts.snapshot);
            assessment.put("observations", facts.observations);
            assessment.put("samples", facts.samples);
            assessment.put("evidence", facts.audit);
            Map<String, Long> statuses = new LinkedHashMap<>();
            facts.observations
                    .forEach(fact -> statuses.merge(fact.getAsJsonObject().get("status").getAsString(), 1L, Long::sum));
            assessment.put("observationStatuses", statuses);
            long attempted = facts.audit.stream().filter(event -> event.containsKey("sql")).count();
            assessment.put("probes_attempted", attempted);
            assessment.put("probes_remaining", Math.max(0, budget.probes - attempted));
            assessment.put("model_budget_charged", false);
        }
    }

    static JsonObject prepareWithAssessment(TiDBGlobalState state, TiDBMixedSupport io, Candidate query,
            JsonObject modelInitial, TiDBMixedPreparationHarness.ClientFactory factory, Map<String, Object> record)
            throws Exception {
        long dataRevision = modelInitial.get("data_revision").getAsLong();
        JsonObject before = assessData(io, query, modelInitial, dataRevision, new JsonArray(),
                new TiDBMixedObservations.Budget(64, 64, 10_000), record, "prePreparationAssessment");
        JsonObject beforeCoverage = TiDBMixedDataCoverage.snapshot(before);
        record.put("dataCoverageBefore", beforeCoverage);
        TiDBMixedPreparationHarness preparationLoop = new TiDBMixedPreparationHarness(state, query, modelInitial,
                List.of(), 10_000, 64, 2);
        preparationLoop.verificationFloor(TiDBMixedDataCoverage
                .measurementIds(new TiDBMixedObservations(query, modelInitial.get("query_id").getAsString(),
                        modelInitial.get("schema_revision").getAsLong(), dataRevision)));
        JsonObject prepared = prepareData(preparationLoop, factory, record);
        JsonObject after = assessData(io, query, modelInitial,
                prepared.getAsJsonObject("final_state").get("data_revision").getAsLong(),
                prepared.getAsJsonArray("model_goals"), new TiDBMixedObservations.Budget(64, 64, 10_000), record,
                "postPreparationAssessment");
        record.put("postPreparationObservations", after.get("observations"));
        record.put("postPreparationEvidence", ((Map<?, ?>) record.get("postPreparationAssessment")).get("evidence"));
        record.put("postPreparationModelGoalEvaluation",
                TiDBMixedPreparationGoals.evaluate(after, prepared.getAsJsonArray("model_goals"), false));
        JsonObject afterCoverage = TiDBMixedDataCoverage.snapshot(after);
        record.put("dataCoverageAfter", afterCoverage);
        record.put("dataCoverageChange", TiDBMixedDataCoverage.difference(beforeCoverage, afterCoverage));
        return prepared;
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
        countedPreflight(io, query.tables);
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
        boolean closedLoop = "DEEPSEEK".equalsIgnoreCase(System.getenv("MS_DATA_PREPARATION"));
        TiDBDeepSeekAdvisor ai = closedLoop ? new TiDBDeepSeekAdvisor(null, "disabled-during-preparation", 0)
                : TiDBDeepSeekAdvisor.fromEnvironment();
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
        report.put("preflightCounts", preflightCounts);
        List<Map<String, String>> persistenceErrors = new ArrayList<>();
        report.put("evidenceSaveErrors", persistenceErrors);
        List<Map<String, Object>> attempts = new ArrayList<>();
        report.put("attempts", attempts);
        boolean mismatch = false;
        boolean passed = false;
        boolean cleanupFailed = false;
        boolean preflightMismatch = false;
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
                Profile profile = closedLoop ? Profile.RANDOM : dataAdvice.action == Action.SET_PROFILE
                        ? dataAdvice.profile : Randomly.fromOptions(Profile.values());
                record.put("dataGeneration", TiDBMixedGeneration.fill(state, query, profile, io));
                TiDBMixedMetadata actualMetadata = TiDBMixedMetadata.collect(io, state.getDatabaseName(), query.tables,
                        revision);
                record.put("actualMetadata", actualMetadata.schema);
                record.put("metadataEvidence", actualMetadata.evidence);
                TiDBMixedObservations facts = new TiDBMixedObservations(query, "query_" + revision, revision, 0);
                JsonObject preparationBudget = closedLoop ? TiDBMixedPreparationHarness.preparationBudget() : null;
                try {
                    if (closedLoop) {
                        facts.bootstrap(io, preparationBudget);
                    } else {
                        facts.collect(io, facts.allIds(), new TiDBMixedObservations.Budget(64, 64, 10_000));
                    }
                    record.put("observationStatus", "COLLECTED");
                } catch (java.sql.SQLException | Rejected error) {
                    record.put("observationStatus", "ERROR");
                    record.put("observationError", error.toString());
                    throw error; // An un-restored session cannot safely enter differential execution.
                } finally {
                    record.put("observations", facts.observations);
                    record.put("samples", facts.samples);
                    record.put("observationEvidence", facts.audit);
                    record.put("availableObservations", facts.available());
                    record.put("observationSnapshot", facts.snapshot);
                }
                if (closedLoop) {
                    JsonObject budget = preparationBudget;
                    JsonObject packet = facts.statePacket("prepare_" + revision,
                            state.getDatabaseName() + "_" + revision, actualMetadata.schema, new JsonArray(), budget);
                    prepareWithAssessment(state, io, query, packet, () -> TiDBDeepSeekPreparationClient
                            .fromEnvironment(TiDBMixedPreparationHarness.preparationCallLimit()), record);
                }
                List<String> dump = io.dump(query.tables);
                record.put("restoreStatements", dump);
                io.replicas(query.tables);
                io.beginSnapshot();
                record.put("snapshot", io.snapshot);
                if (!preflightReady(io, query.tables, record)) {
                    preflightMismatch = true;
                    report.put("status", "DATA_PREFLIGHT_MISMATCH");
                    history.merge("DATA_PREFLIGHT_MISMATCH", 1L, Long::sum);
                    break; // Data equivalence is a prerequisite; do not execute the mixed query or another revision.
                }
                List<Config> configs = TiDBMixedSupport.configurations(names(query));
                List<Run> runs = executeWithCoverage(io, query, configs, record, "coverage");
                Outcome outcome = TiDBMixedSupport.compare(runs);
                record.put("runs", runs);
                record.put("outcome", outcome);
                report.put("status", outcome.status);
                report.put("coverage", coverage.snapshot());
                history.merge(outcome.status, 1L, Long::sum);
                mismatch = outcome.mismatch();
                if (mismatch) {
                    record.put("mismatchRunSet", "runs");
                }
                saveRequired(directory.resolve("case.json"), report, persistenceErrors);
                if (mismatch) {
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
                        List<Run> alternate = executeWithCoverage(io, query, alternatives, record,
                                "alternativeCoverage");
                        report.put("coverage", coverage.snapshot());
                        Outcome alternativeOutcome = TiDBMixedSupport.compare(alternate);
                        record.put("alternativeRuns", alternate);
                        record.put("alternativeOutcome", alternativeOutcome);
                        mismatch = alternativeOutcome.mismatch();
                        if (mismatch) {
                            record.put("mismatchRunSet", "alternativeRuns");
                            report.put("status", alternativeOutcome.status);
                            saveRequired(directory.resolve("case.json"), report, persistenceErrors);
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
            report.put("preflightCounts", preflightCounts());
            report.put("coverage", coverage.snapshot());
            if (!saveFinalEvidence(directory, report, history, preflightCounts(), coverage.snapshot(),
                    preflightMismatch, mismatch, persistenceErrors)) {
                passed = false;
            }
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
