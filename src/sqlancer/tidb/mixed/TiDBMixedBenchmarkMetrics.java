package sqlancer.tidb.mixed;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Numerators, denominators and missing measurements remain explicit. */
public final class TiDBMixedBenchmarkMetrics {
    private TiDBMixedBenchmarkMetrics() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Metrics all.json summary.json");
        }
        JsonArray reports = com.google.gson.JsonParser
                .parseString(java.nio.file.Files.readString(java.nio.file.Path.of(args[0]))).getAsJsonArray();
        TiDBMixedSupport.save(java.nio.file.Path.of(args[1]), summarize(reports));
    }

    private static void add(Map<String, Long> counts, String key, long value) {
        counts.merge(key, value, Long::sum);
    }

    private static void rounds(JsonObject report, Map<String, Long> counts) {
        if (!report.has("preparation")) {
            return;
        }
        for (JsonElement element : report.getAsJsonObject("preparation").getAsJsonArray("rounds")) {
            JsonObject round = element.getAsJsonObject();
            add(counts, "responses_" + round.get("validation_status").getAsString(), 1);
            if (round.has("failure_reason") && !round.get("failure_reason").isJsonNull()
                    && round.get("failure_reason").getAsString().contains("repeated failed row")) {
                add(counts, "repeated_failure_rejections", 1);
            }
            if ("observe".equals(proposedAction(round))) {
                add(counts, "observe_plans_proposed", 1);
                if (round.has("probe_audit")) {
                    add(counts, "observe_plans_executed", 1);
                    add(counts, "observe_probes", round.getAsJsonArray("probe_audit").size());
                }
            }
            if (round.has("probe_audit")) {
                for (JsonElement probe : round.getAsJsonArray("probe_audit")) {
                    if (probe.getAsJsonObject().has("parameters")) {
                        add(counts, "parameterized_probes", 1);
                    }
                }
            }
            if (!"append_rows".equals(proposedAction(round))) {
                continue;
            }
            add(counts, "append_plans_proposed", 1);
            long success = 0;
            for (JsonElement row : round.getAsJsonArray("writes")) {
                String status = row.getAsJsonObject().get("status").getAsString();
                add(counts, "rows_" + status, 1);
                if (status.equals("success")) {
                    success++;
                }
            }
            if (round.has("append_feedback")
                    && round.getAsJsonObject("append_feedback").get("attempted_rows").getAsLong() > 0) {
                add(counts, "append_plans_executed", 1);
            }
            String outcome = round.getAsJsonObject("evaluation").get("outcome").getAsString();
            add(counts, "append_goals_" + outcome, 1);
            if (round.has("model_goal_evaluation")) {
                add(counts, "append_model_goals_"
                        + round.getAsJsonObject("model_goal_evaluation").get("outcome").getAsString(), 1);
            }
            if (success > 0 && round.has("data_coverage_change")) {
                JsonObject change = round.getAsJsonObject("data_coverage_change");
                scaleRowGain(change, counts, success, "");
                if (!change.has("query_stages")
                        || !change.getAsJsonObject("query_stages").get("complete").getAsBoolean()) {
                    add(counts, "rows_gain_unverified", success);
                } else if (change.getAsJsonObject("query_stages").getAsJsonArray("added").isEmpty()) {
                    add(counts, "rows_without_new_scene", success);
                    if (change.has("base_data")
                            && !change.getAsJsonObject("base_data").getAsJsonArray("added").isEmpty()) {
                        add(counts, "base_data_only_append_rounds", 1);
                    }
                } else {
                    add(counts, "productive_append_rounds", 1);
                }
            }
        }
    }

    private static void caseRowGain(JsonObject report, Map<String, Long> counts) {
        if (!report.has("preparation")) {
            return;
        }
        long successful = 0;
        for (JsonElement entry : report.getAsJsonObject("preparation").getAsJsonArray("rounds")) {
            for (JsonElement row : entry.getAsJsonObject().getAsJsonArray("writes")) {
                successful += row.getAsJsonObject().get("status").getAsString().equals("success") ? 1 : 0;
            }
        }
        JsonObject change = report.has("data_coverage_change") ? report.getAsJsonObject("data_coverage_change")
                : new JsonObject();
        scaleRowGain(change, counts, successful, "case_");
        if (!change.has("query_stages") || !change.getAsJsonObject("query_stages").get("complete").getAsBoolean()) {
            add(counts, "case_rows_query_gain_unverified", successful);
        } else if (change.getAsJsonObject("query_stages").getAsJsonArray("added").isEmpty()) {
            add(counts, "case_rows_without_new_query_scene", successful);
        } else {
            // Case-level association only: do not attribute the gain to each particular inserted row.
            add(counts, "case_rows_with_new_query_scene", successful);
        }
    }

    private static void scaleRowGain(JsonObject change, Map<String, Long> counts, long rows, String prefix) {
        JsonObject scale = change.has("scale") && change.getAsJsonObject("scale").has("query_stages")
                ? change.getAsJsonObject("scale").getAsJsonObject("query_stages") : null;
        if (scale != null && !scale.getAsJsonArray("increased").isEmpty()) {
            add(counts, prefix + "rows_with_query_scale_expansion", rows);
            if (prefix.isEmpty()) {
                add(counts, "scale_expanding_append_rounds", 1);
            }
        } else if (scale == null || !scale.get("complete").getAsBoolean()) {
            add(counts, prefix + "rows_scale_gain_unverified", rows);
        } else if (scale.getAsJsonArray("changes").isEmpty()) {
            add(counts, prefix + "rows_without_query_scale_change", rows);
        } else {
            add(counts, prefix + "rows_with_query_scale_contraction_only", rows);
        }
    }

    private static JsonObject scopedScale(JsonArray reports, String strategy, String scope) {
        JsonObject result = new JsonObject();
        long missing = 0;
        long complete = 0;
        long unverified = 0;
        long increases = 0;
        long decreases = 0;
        long transitions = 0;
        for (JsonElement entry : reports) {
            JsonObject report = entry.getAsJsonObject();
            if (!strategy.equals(report.get("strategy").getAsString())) {
                continue;
            }
            JsonObject change = report.has("data_coverage_change") ? report.getAsJsonObject("data_coverage_change")
                    : null;
            if (change == null || !change.has("scale")) {
                missing++;
                continue;
            }
            JsonObject scale = change.getAsJsonObject("scale").getAsJsonObject(scope);
            increases += scale.getAsJsonArray("increased").size();
            decreases += scale.getAsJsonArray("decreased").size();
            transitions += scale.getAsJsonArray("bucket_transitions").size();
            if (scale.get("complete").getAsBoolean()) {
                complete++;
            } else {
                unverified++;
            }
        }
        result.addProperty("increased_metrics", increases);
        result.addProperty("decreased_metrics", decreases);
        result.addProperty("bucket_transitions", transitions);
        result.addProperty("complete_changes", complete);
        result.addProperty("unverified_changes", unverified);
        result.addProperty("missing_scale_reports", missing);
        result.addProperty("meaning", "Magnitude changes, not first-time scenes or evidence of higher bug yield");
        return result;
    }

    private static String proposedAction(JsonObject round) {
        if (!round.get("decision").isJsonNull()) {
            return round.getAsJsonObject("decision").get("decision").getAsString();
        }
        if (round.has("raw_response")) {
            try {
                JsonObject raw = com.google.gson.JsonParser.parseString(round.get("raw_response").getAsString())
                        .getAsJsonObject();
                if (raw.has("tool_calls") && raw.getAsJsonArray("tool_calls").size() == 1) {
                    return raw.getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function")
                            .get("name").getAsString();
                }
                return raw.get("decision").getAsString();
            } catch (RuntimeException invalid) {
                return "unparseable"; // Audit classification only, never execution authorization.
            }
        }
        return "unavailable";
    }

    private static JsonObject rate(long numerator, long denominator) {
        JsonObject rate = new JsonObject();
        rate.addProperty("numerator", numerator);
        rate.addProperty("denominator", denominator);
        rate.add("ratio", denominator == 0 ? JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive((double) numerator / denominator));
        return rate;
    }

    private static void mergeCoverage(JsonObject destination, JsonObject source) {
        for (Map.Entry<String, JsonElement> bucket : source.entrySet()) {
            JsonObject total = destination.has(bucket.getKey()) ? destination.getAsJsonObject(bucket.getKey())
                    : new JsonObject();
            JsonObject incoming = bucket.getValue().getAsJsonObject();
            for (String key : java.util.List.of("attempted", "valid", "failed")) {
                total.addProperty(key,
                        (total.has(key) ? total.get(key).getAsLong() : 0) + incoming.get(key).getAsLong());
            }
            destination.add(bucket.getKey(), total);
        }
    }

    private static JsonObject scopedCoverage(JsonArray reports, String strategy, String scope) {
        JsonObject result = new JsonObject();
        long added = 0;
        long lost = 0;
        long effective = 0;
        long complete = 0;
        long unverified = 0;
        long missing = 0;
        java.util.Set<String> buckets = new TreeSet<>();
        for (JsonElement entry : reports) {
            JsonObject report = entry.getAsJsonObject();
            if (!strategy.equals(report.get("strategy").getAsString())) {
                continue;
            }
            JsonObject change = report.has("data_coverage_change") ? report.getAsJsonObject("data_coverage_change")
                    : new JsonObject();
            if (!change.has(scope)) {
                missing++;
                continue; // Historical unscoped coverage cannot be upgraded into stage coverage.
            }
            JsonObject scoped = change.getAsJsonObject(scope);
            int hits = scoped.getAsJsonArray("added").size();
            added += hits;
            lost += scoped.getAsJsonArray("lost").size();
            if (scoped.get("complete").getAsBoolean()) {
                complete++;
            } else {
                unverified++;
            }
            scoped.getAsJsonArray("added").forEach(bucket -> buckets.add(bucket.getAsString()));
            if (scope.equals("query_stages") && report.has("engine_outcome")
                    && report.getAsJsonObject("engine_outcome").get("allPathsValid").getAsBoolean()) {
                effective += hits;
            }
        }
        result.addProperty("new_hits", added);
        result.addProperty("lost_hits", lost);
        result.addProperty("complete_changes", complete);
        result.addProperty("unverified_changes", unverified);
        result.addProperty("missing_scoped_reports", missing);
        result.add("new_buckets", new com.google.gson.Gson().toJsonTree(buckets));
        if (scope.equals("query_stages")) {
            result.addProperty("new_effective_scene_hits", effective);
        }
        return result;
    }

    public static JsonObject summarize(JsonArray reports) {
        JsonObject summary = new JsonObject();
        for (String strategy : java.util.List.of("RANDOM", "SHARED", "AI", "AI_THINKING")) {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String key : java.util.List.of("cases", "completed_cases", "append_plans_proposed",
                    "append_plans_executed", "append_goals_achieved", "append_goals_not_achieved",
                    "append_goals_unverified", "responses_accepted", "responses_rejected", "rows_success",
                    "rows_failed", "rows_unknown", "rows_not_attempted", "rows_without_new_scene",
                    "rows_gain_unverified", "productive_append_rounds", "repeated_failure_rejections",
                    "final_goals_achieved", "final_goals_not_achieved", "final_goals_unverified",
                    "new_observed_scene_hits", "new_effective_scene_hits", "lost_scene_hits", "engine_paths_attempted",
                    "engine_paths_valid", "engine_all_paths_valid_cases", "api_calls", "prompt_tokens",
                    "completion_tokens", "preparation_elapsed_ms", "total_elapsed_ms", "legacy_unscoped_scene_hits",
                    "base_data_only_append_rounds", "observe_plans_proposed", "observe_plans_executed",
                    "observe_probes", "parameterized_probes", "case_rows_without_new_query_scene",
                    "case_rows_with_new_query_scene", "case_rows_query_gain_unverified",
                    "rows_with_query_scale_expansion", "rows_scale_gain_unverified", "rows_without_query_scale_change",
                    "rows_with_query_scale_contraction_only", "scale_expanding_append_rounds",
                    "case_rows_with_query_scale_expansion", "case_rows_scale_gain_unverified",
                    "case_rows_without_query_scale_change", "case_rows_with_query_scale_contraction_only")) {
                counts.put(key, 0L);
            }
            BigDecimal cost = BigDecimal.ZERO;
            boolean costComplete = true;
            java.util.Set<String> buckets = new TreeSet<>();
            JsonObject engineRatios = new JsonObject();
            JsonObject engineJoins = new JsonObject();
            for (JsonElement element : reports) {
                JsonObject report = element.getAsJsonObject();
                if (!strategy.equals(report.get("strategy").getAsString())) {
                    continue;
                }
                add(counts, "cases", 1);
                add(counts, "completed_cases", report.get("status").getAsString().equals("COMPLETED") ? 1 : 0);
                rounds(report, counts);
                caseRowGain(report, counts);
                add(counts, "total_elapsed_ms", report.get("elapsed_ms").getAsLong());
                if (report.has("preparation_elapsed_ms")) {
                    add(counts, "preparation_elapsed_ms", report.get("preparation_elapsed_ms").getAsLong());
                }
                if (report.has("final_goal_evaluation")) {
                    add(counts, "final_goals_"
                            + report.getAsJsonObject("final_goal_evaluation").get("outcome").getAsString(), 1);
                }
                if (report.has("final_model_goal_evaluation")) {
                    add(counts, "final_model_goals_"
                            + report.getAsJsonObject("final_model_goal_evaluation").get("outcome").getAsString(), 1);
                }
                boolean allPaths = report.has("engine_outcome")
                        && report.getAsJsonObject("engine_outcome").get("allPathsValid").getAsBoolean();
                add(counts, "engine_all_paths_valid_cases", allPaths ? 1 : 0);
                if (report.has("engine_coverage")) {
                    JsonObject engineCoverage = report.getAsJsonObject("engine_coverage");
                    mergeCoverage(engineRatios, engineCoverage.getAsJsonObject("ratios"));
                    mergeCoverage(engineJoins, engineCoverage.getAsJsonObject("joins"));
                }
                if (report.has("engine_runs")) {
                    for (JsonElement run : report.getAsJsonArray("engine_runs")) {
                        add(counts, "engine_paths_attempted", 1);
                        add(counts, "engine_paths_valid",
                                "OK".equals(run.getAsJsonObject().get("status").getAsString()) ? 1 : 0);
                    }
                }
                if (report.has("data_coverage_change")) {
                    JsonObject change = report.getAsJsonObject("data_coverage_change");
                    if (change.has("query_stages")) {
                        JsonObject queryStages = change.getAsJsonObject("query_stages");
                        add(counts, "new_observed_scene_hits", queryStages.getAsJsonArray("added").size());
                        add(counts, "new_effective_scene_hits",
                                allPaths ? queryStages.getAsJsonArray("added").size() : 0);
                        add(counts, "lost_scene_hits", queryStages.getAsJsonArray("lost").size());
                        queryStages.getAsJsonArray("added").forEach(b -> buckets.add(b.getAsString()));
                    } else {
                        add(counts, "legacy_unscoped_scene_hits", change.getAsJsonArray("added").size());
                    }
                }
                for (JsonElement entry : report.getAsJsonArray("api_audit")) {
                    JsonObject api = entry.getAsJsonObject();
                    add(counts, "api_calls", 1);
                    if (api.has("usage")) {
                        JsonObject usage = api.getAsJsonObject("usage");
                        add(counts, "prompt_tokens", usage.get("prompt_tokens").getAsLong());
                        add(counts, "completion_tokens", usage.get("completion_tokens").getAsLong());
                    }
                    if (api.get("estimated_usd").isJsonNull()) {
                        costComplete = false;
                    } else {
                        cost = cost.add(api.get("estimated_usd").getAsBigDecimal());
                    }
                }
            }
            JsonObject result = new com.google.gson.Gson().toJsonTree(counts).getAsJsonObject();
            result.addProperty("coverage_scope_version", 3);
            result.add("new_scene_buckets", new com.google.gson.Gson().toJsonTree(buckets));
            result.add("base_data", scopedCoverage(reports, strategy, "base_data"));
            result.add("query_stages", scopedCoverage(reports, strategy, "query_stages"));
            result.add("base_data_scale", scopedScale(reports, strategy, "base_data"));
            result.add("query_stage_scale", scopedScale(reports, strategy, "query_stages"));
            result.addProperty("row_gain_semantics",
                    "Rows are associated with case/round changes, not individually attributed; without_new_scene means no boolean first hit, not no benefit");
            result.add("engine_ratio_buckets", engineRatios);
            result.add("engine_join_buckets", engineJoins);
            result.addProperty("known_estimated_usd", cost);
            result.addProperty("cost_complete", costComplete);
            result.add("accepted_response_per_api_call_rate",
                    rate(counts.get("api_calls") == 0 ? 0 : counts.get("responses_accepted"), counts.get("api_calls")));
            result.add("protocol_acceptance_rate", rate(counts.get("responses_accepted"),
                    counts.get("responses_accepted") + counts.get("responses_rejected")));
            result.add("plan_execution_rate",
                    rate(counts.get("append_plans_executed"), counts.get("append_plans_proposed")));
            result.add("verifiable_append_goal_rate", rate(counts.get("append_goals_achieved"),
                    counts.get("append_goals_achieved") + counts.get("append_goals_not_achieved")));
            result.add("final_case_goal_rate", rate(counts.get("final_goals_achieved"), counts.get("cases")));
            long achieved = counts.getOrDefault("append_model_goals_achieved", 0L);
            long notAchieved = counts.getOrDefault("append_model_goals_not_achieved", 0L);
            result.add("verifiable_append_model_goal_rate", rate(achieved, achieved + notAchieved));
            result.add("final_case_model_goal_rate",
                    rate(counts.getOrDefault("final_model_goals_achieved", 0L), counts.get("cases")));
            summary.add(strategy, result);
        }
        return summary;
    }
}
