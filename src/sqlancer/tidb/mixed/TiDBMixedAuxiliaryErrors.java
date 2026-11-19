package sqlancer.tidb.mixed;

import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

/** Immediate, independent evidence for failed auxiliary statements, including cases whose differential passes. */
public final class TiDBMixedAuxiliaryErrors {
    private final Path directory;
    public final Map<String, Object> summary = new LinkedHashMap<>();
    private final List<Map<String, Object>> entries = new ArrayList<>();
    private final List<Map<String, String>> saveFailures = new ArrayList<>();
    private int internal;

    public TiDBMixedAuxiliaryErrors(Path directory) {
        this.directory = directory;
        summary.put("errors", entries);
        summary.put("save_failures", saveFailures);
        summary.put("internal_candidates", 0);
    }

    static boolean internal(SQLException error) {
        for (SQLException cause = error; cause != null; cause = cause.getNextException()) {
            String text = String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
            if (text.contains("runtime error:") || text.contains("panic") || text.contains("assertion failed")
                    || text.contains("invalid memory address")) {
                return true;
            }
        }
        return false;
    }

    private void save(Path file, Object value) {
        TiDBAiMixedOracle.saveEvidence(file, value, saveFailures);
    }

    void record(TiDBMixedSupport io, Candidate candidate, Map<String, Object> context, SQLException error) {
        Map<String, Object> evidence = new LinkedHashMap<>(context);
        boolean candidateError = internal(error);
        evidence.put("status", candidateError ? "INTERNAL_ERROR_CANDIDATE" : "OBSERVATION_READ_ERROR");
        evidence.put("confirmed", false);
        evidence.put("original_query", candidate.sql);
        evidence.put("tidbCommit", TiDBMixedTarget.COMMIT);
        evidence.put("error", error.toString());
        evidence.put("sqlstate", error.getSQLState());
        evidence.put("error_code", error.getErrorCode());
        List<Map<String, Object>> chain = new ArrayList<>();
        for (SQLException cause = error; cause != null; cause = cause.getNextException()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("message", cause.getMessage());
            item.put("sqlstate", cause.getSQLState());
            item.put("error_code", cause.getErrorCode());
            chain.add(item);
        }
        evidence.put("exception_chain", chain);
        Path file = directory.resolve("error-" + entries.size() + ".json");
        entries.add(evidence);
        if (candidateError) {
            summary.put("internal_candidates", ++internal);
        }
        // Persist the error first: even a disconnected server or failed dump must leave the SQL and bindings.
        evidence.put("replay_status", "NOT_CAPTURED");
        save(file, evidence);
        if (candidateError) {
            try {
                evidence.put("session",
                        io.textRows("SELECT @@sql_mode AS sql_mode, @@time_zone AS time_zone,"
                                + " @@character_set_connection AS character_set_connection,"
                                + " @@collation_connection AS collation_connection,"
                                + " @@tidb_opt_enable_advanced_join_reorder AS tidb_opt_enable_advanced_join_reorder,"
                                + " @@tidb_enable_outer_join_reorder AS tidb_enable_outer_join_reorder,"
                                + " @@tidb_opt_join_reorder_threshold AS tidb_opt_join_reorder_threshold"));
                evidence.put("restoreStatements", io.dump(candidate.tables));
                evidence.put("replay_status", "CAPTURED");
            } catch (SQLException | RuntimeException capture) {
                evidence.put("replay_status", "CAPTURE_FAILED");
                evidence.put("capture_error", capture.toString());
            }
            save(file, evidence);
        }
        save(directory.resolve("index.json"), summary);
    }

    void reportToSqlancer() {
        if (internal > 0) {
            throw new AssertionError("Auxiliary query internal error candidate (not a confirmed discrepancy); inspect "
                    + directory + "; candidates=" + internal);
        }
        if (!saveFailures.isEmpty()) {
            throw new AssertionError("Auxiliary query evidence could not be saved; inspect " + directory);
        }
    }
}
