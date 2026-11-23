package sqlancer.tidb.mixed;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

/** Immediate, independent evidence for failed auxiliary statements, including cases whose differential passes. */
public final class TiDBMixedAuxiliaryErrors {
    private final Path directory;
    public final Map<String, Object> summary = new LinkedHashMap<>();
    private final List<Map<String, Object>> entries = new ArrayList<>();
    private final List<Map<String, String>> saveFailures = new ArrayList<>();
    private final Map<String, Map<String, Object>> groups = new LinkedHashMap<>();
    private int internal;
    private int internalGroups;

    public TiDBMixedAuxiliaryErrors(Path directory) {
        this.directory = directory;
        summary.put("errors", entries);
        summary.put("save_failures", saveFailures);
        summary.put("internal_candidates", 0); // Legacy field counts occurrences, never independent bugs.
        summary.put("internal_error_occurrences", 0);
        summary.put("distinct_internal_error_signatures", 0);
        summary.put("error_occurrences", 0);
        summary.put("distinct_error_signatures", 0);
        summary.put("grouping_scope", "SQL_BINDINGS_EXCEPTION_CHAIN");
        summary.put("root_cause_verified", false);
        summary.put("signature_groups", new ArrayList<>());
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

    static List<Map<String, Object>> exceptionChain(SQLException error) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (SQLException cause = error; cause != null; cause = cause.getNextException()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("message", cause.getMessage());
            item.put("sqlstate", cause.getSQLState());
            item.put("error_code", cause.getErrorCode());
            result.add(item);
        }
        return result;
    }

    // Exact statement/error identity only; matching signatures do not establish a shared root cause.
    static String signature(JsonObject evidence) {
        JsonObject key = new JsonObject();
        key.add("sql", evidence.get("sql"));
        JsonArray bindings = new JsonArray();
        if (evidence.has("bindings")) {
            for (JsonElement entry : evidence.getAsJsonArray("bindings")) {
                JsonObject cell = new JsonObject();
                cell.add("type", entry.getAsJsonObject().get("type"));
                cell.add("value", entry.getAsJsonObject().get("value"));
                bindings.add(cell);
            }
        }
        key.add("bindings", bindings);
        JsonArray chain = new JsonArray();
        if (evidence.has("exception_chain")) {
            for (JsonElement entry : evidence.getAsJsonArray("exception_chain")) {
                JsonObject cause = new JsonObject();
                for (String field : List.of("message", "sqlstate", "error_code")) {
                    cause.add(field, entry.getAsJsonObject().get(field));
                }
                chain.add(cause);
            }
        }
        key.add("exception_chain", chain);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Hash exact Java code units, including unpaired surrogates; encoding replacement would merge evidence.
            String serialized = key.toString();
            for (int i = 0; i < serialized.length(); i++) {
                digest.update((byte) (serialized.charAt(i) >>> 8));
                digest.update((byte) serialized.charAt(i));
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
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
        evidence.put("exception_chain", exceptionChain(error));
        String signature = signature(new Gson().toJsonTree(evidence).getAsJsonObject());
        evidence.put("error_signature", signature);
        String filename = "error-" + entries.size() + ".json";
        evidence.put("occurrence_file", filename);
        Path file = directory.resolve(filename);
        entries.add(evidence);
        Map<String, Object> group = groups.get(signature);
        if (group == null) {
            group = new LinkedHashMap<>();
            group.put("error_signature", signature);
            group.put("status", evidence.get("status"));
            group.put("confirmed", false);
            group.put("occurrence_files", new ArrayList<String>());
            group.put("occurrences", 0);
            groups.put(signature, group);
            if (candidateError) {
                internalGroups++;
            }
        }
        @SuppressWarnings("unchecked")
        List<String> files = (List<String>) group.get("occurrence_files");
        files.add(filename);
        group.put("occurrences", files.size());
        summary.put("signature_groups", new ArrayList<>(groups.values()));
        summary.put("error_occurrences", entries.size());
        summary.put("distinct_error_signatures", groups.size());
        summary.put("distinct_internal_error_signatures", internalGroups);
        if (candidateError) {
            summary.put("internal_candidates", ++internal);
            summary.put("internal_error_occurrences", internal);
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
                    + directory + "; occurrences=" + internal + "; statement_error_signatures=" + internalGroups);
        }
        if (!saveFailures.isEmpty()) {
            throw new AssertionError("Auxiliary query evidence could not be saved; inspect " + directory);
        }
    }
}
