package sqlancer.tidb.mixed;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

/** Deterministic preparation experiment with an injected responder, never an Oracle verdict. */
public final class TiDBMixedPreparationHarness {
    private final TiDBMixedSupport io;
    private final Candidate candidate;
    private final TiDBMixedAppendExecutor executor;
    private final List<Requirement> requirements;
    private final List<JsonObject> failedRows = new ArrayList<>();
    private final int sampleRows;
    private final int failureLimit;
    private final JsonArray rounds = new JsonArray();
    private final JsonObject packet;
    private List<String> verificationFloor = List.of();
    private int batch;
    private int failedRounds;
    private boolean used;
    private boolean uncertain;

    @FunctionalInterface
    public interface Responder {
        String respond(List<Map<String, String>> messages) throws Exception;
    }

    private static final class RepeatedFailedRow extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private RepeatedFailedRow() {
            super("repeated failed row: change the plan; prose is not new evidence");
        }
    }

    public static JsonObject preparationBudget() {
        return preparationBudget(Long.parseLong(System.getenv().getOrDefault("MS_PREPARATION_ROWS", "6")));
    }

    static JsonObject preparationBudget(long rows) {
        if (rows < 0 || rows > 1000) {
            throw new IllegalArgumentException("MS_PREPARATION_ROWS must be between 0 and 1000");
        }
        JsonObject budget = new JsonObject();
        budget.addProperty("model_calls", 3);
        budget.addProperty("probe_requests", 96);
        budget.addProperty("insert_rows", rows);
        budget.addProperty("time_ms", 120_000);
        return budget;
    }

    /** Test-owned, measurable assertions. Natural language goals are deliberately not parsed. */
    public static final class Requirement {
        private final String capability;
        private final String field;
        private final String expected;
        private final boolean minimum;

        private Requirement(String capability, String field, String expected, boolean minimum) {
            this.capability = capability;
            this.field = field;
            this.expected = expected;
            this.minimum = minimum;
        }

        public static Requirement count(String capability, long expected, boolean minimum) {
            if (expected < 0) {
                throw new IllegalArgumentException("negative expected count");
            }
            return new Requirement(capability, "count", Long.toString(expected), minimum);
        }

        public static Requirement empty(boolean expected) {
            return new Requirement("query_empty", "empty", Boolean.toString(expected), false);
        }
    }

    public TiDBMixedPreparationHarness(TiDBGlobalState state, Candidate candidate, JsonObject initial,
            List<Requirement> requirements, int readLimit, int sampleRows, int failureLimit) {
        if (sampleRows < 1 || sampleRows >= Integer.MAX_VALUE || failureLimit < 1) {
            throw new IllegalArgumentException("invalid preparation limits");
        }
        packet = TiDBDataPreparationProtocol.state(initial.toString());
        this.candidate = candidate;
        this.requirements = List.copyOf(requirements);
        this.sampleRows = sampleRows;
        this.failureLimit = failureLimit;
        io = new TiDBMixedSupport(state);
        TiDBMixedObservations facts = facts();
        JsonObject described = facts.statePacket("check", packet.get("case_id").getAsString(),
                packet.getAsJsonObject("schema"), new JsonArray(), packet.getAsJsonObject("remaining_budget"));
        if (!described.get("query").equals(packet.get("query"))
                || !facts.available().equals(packet.get("available_observations"))) {
            throw new IllegalArgumentException("candidate or observation catalog differs from state packet");
        }
        executor = new TiDBMixedAppendExecutor(state, packet, readLimit);
    }

    private TiDBMixedObservations facts() {
        return new TiDBMixedObservations(candidate, packet.get("query_id").getAsString(),
                packet.get("schema_revision").getAsLong(), packet.get("data_revision").getAsLong());
    }

    private long budget(String key) {
        return packet.getAsJsonObject("remaining_budget").get(key).getAsLong();
    }

    public void verificationFloor(List<String> ids) {
        if (used || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("set a unique verification floor before running");
        }
        List<String> available = new ArrayList<>();
        packet.getAsJsonArray("available_observations")
                .forEach(e -> available.add(e.getAsJsonObject().get("id").getAsString()));
        if (!available.containsAll(ids)) {
            throw new IllegalArgumentException("unavailable verification floor");
        }
        verificationFloor = List.copyOf(ids);
    }

    private JsonArray verification(JsonObject plan) {
        java.util.Set<String> ids = new LinkedHashSet<>(verificationFloor);
        plan.getAsJsonArray("verification_requests").forEach(id -> ids.add(id.getAsString()));
        if (ids.size() > budget("probe_requests")) {
            throw new IllegalArgumentException("verification floor exceeds remaining probe budget");
        }
        JsonArray requests = new JsonArray();
        ids.forEach(requests::add);
        return requests;
    }

    private void budget(String key, long value) {
        packet.getAsJsonObject("remaining_budget").addProperty(key, Math.max(0, value));
    }

    private void observe(JsonArray requests, JsonObject round) throws SQLException {
        // A new batch replaces old evidence, even after a failed write. IDs remain unique across batches.
        packet.add("observations", new JsonArray());
        packet.add("samples", new JsonArray());
        if (requests.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        requests.forEach(id -> ids.add(id.getAsString()));
        TiDBMixedObservations facts = facts();
        budget("probe_requests", budget("probe_requests") - ids.size());
        facts.collect(io, ids, new TiDBMixedObservations.Budget(ids.size(), sampleRows, budget("time_ms")));
        String prefix = "batch_" + ++batch + "_";
        for (JsonArray entries : List.of(facts.observations, facts.samples)) {
            entries.forEach(entry -> {
                JsonObject object = entry.getAsJsonObject();
                object.addProperty("id", prefix + object.get("id").getAsString());
            });
        }
        facts.audit.forEach(event -> event.put("observation_id", prefix + event.get("observation_id")));
        packet.add("observations", facts.observations.deepCopy());
        packet.add("samples", facts.samples.deepCopy());
        round.add("probe_audit", new com.google.gson.Gson().toJsonTree(facts.audit));
        round.addProperty("observation_snapshot", facts.snapshot);
    }

    private static JsonObject rowKey(String table, JsonObject values) {
        JsonObject key = new JsonObject();
        key.addProperty("table", table);
        JsonObject normalized = values.deepCopy();
        normalized.entrySet().forEach(entry -> {
            JsonObject cell = entry.getValue().getAsJsonObject();
            if (List.of("integer", "decimal").contains(cell.get("type").getAsString())) {
                cell.addProperty("value",
                        new BigDecimal(cell.get("value").getAsString()).stripTrailingZeros().toPlainString());
            }
        });
        key.add("values", normalized);
        return key;
    }

    private void rejectRepeatedFailure(JsonObject plan) {
        for (JsonElement operation : plan.getAsJsonArray("operations")) {
            JsonObject object = operation.getAsJsonObject();
            for (JsonElement row : object.getAsJsonArray("rows")) {
                if (failedRows.contains(rowKey(object.get("table").getAsString(), row.getAsJsonObject()))) {
                    throw new RepeatedFailedRow();
                }
            }
        }
    }

    private void append(JsonObject plan, JsonObject round) throws SQLException {
        JsonObject feedback = executor.execute(plan.toString(), packet);
        round.add("append_feedback", feedback);
        round.add("writes", feedback.get("writes").deepCopy());
        if (!feedback.get("failure_reason").isJsonNull()) {
            round.add("failure_reason", feedback.get("failure_reason"));
        }
        uncertain = feedback.get("requires_rebuild").getAsBoolean();
        boolean changed = false;
        for (JsonElement entry : feedback.getAsJsonArray("writes")) {
            JsonObject row = entry.getAsJsonObject();
            String status = row.get("status").getAsString();
            changed |= status.equals("success");
            if (status.equals("failed")) {
                failedRows.add(rowKey(row.get("table").getAsString(), row.getAsJsonObject("specified_values")));
            }
        }
        failedRounds = changed ? 0 : failedRounds + 1;
        budget("insert_rows", Math.min(budget("insert_rows"), feedback.get("remaining_insert_rows").getAsLong()));
        budget("time_ms", Math.min(budget("time_ms"), feedback.get("remaining_time_ms").getAsLong()));
        packet.add("observations", new JsonArray());
        packet.add("samples", new JsonArray());
        if (!uncertain) {
            packet.add("data_revision", feedback.get("data_revision_after"));
            observe(verification(plan), round);
        }
    }

    private static boolean current(JsonObject observation, JsonObject packet) {
        for (String key : List.of("query_id", "schema_revision", "data_revision")) {
            if (!packet.get(key).equals(observation.get(key))) {
                return false;
            }
        }
        return "exact".equals(observation.get("status").getAsString()) && !observation.get("value").isJsonNull();
    }

    public static JsonObject evaluate(JsonObject state, List<Requirement> requirements) {
        JsonObject packet = TiDBDataPreparationProtocol.state(state.toString());
        JsonArray checks = new JsonArray();
        boolean missing = requirements.isEmpty();
        boolean failed = false;
        for (Requirement requirement : requirements) {
            JsonObject check = new JsonObject();
            check.addProperty("capability_id", requirement.capability);
            check.addProperty("field", requirement.field);
            check.addProperty("comparison", requirement.minimum ? "at_least" : "equals");
            check.addProperty("expected", requirement.expected);
            JsonObject evidence = null;
            for (JsonElement entry : packet.getAsJsonArray("observations")) {
                JsonObject observation = entry.getAsJsonObject();
                if (requirement.capability.equals(observation.get("capability_id").getAsString())
                        && current(observation, packet)
                        && observation.getAsJsonObject("value").has(requirement.field)) {
                    evidence = observation;
                }
            }
            String outcome = "unverified";
            if (evidence != null) {
                String actual = evidence.getAsJsonObject("value").get(requirement.field).getAsString();
                boolean satisfied = requirement.minimum
                        ? new BigInteger(actual).compareTo(new BigInteger(requirement.expected)) >= 0
                        : requirement.field.equals("count")
                                ? new BigInteger(actual).equals(new BigInteger(requirement.expected))
                                : actual.equals(requirement.expected);
                outcome = satisfied ? "achieved" : "not_achieved";
                failed |= !satisfied;
            } else {
                missing = true;
            }
            check.add("evidence", evidence == null ? JsonNull.INSTANCE : evidence.deepCopy());
            check.addProperty("outcome", outcome);
            checks.add(check);
        }
        JsonObject result = new JsonObject();
        result.add("checks", checks);
        result.addProperty("outcome", failed ? "not_achieved" : missing ? "unverified" : "achieved");
        return result;
    }

    public JsonObject run(Responder responder) throws Exception {
        if (used) {
            throw new IllegalStateException("a preparation experiment runs once");
        }
        used = true;
        String termination = "budget_exhausted";
        String requestPrefix = packet.get("request_id").getAsString();
        try {
            while (budget("model_calls") > 0 && budget("time_ms") > 0) {
                if (packet.get("frozen").getAsBoolean() || packet.get("discrepancy_found").getAsBoolean()) {
                    termination = "preparation_closed";
                    break;
                }
                packet.addProperty("request_id", requestPrefix + "_round_" + rounds.size());
                JsonObject round = new JsonObject();
                round.add("state_before", packet.deepCopy());
                round.add("writes", new JsonArray());
                round.add("model_expectation", JsonNull.INSTANCE);
                round.add("decision", JsonNull.INSTANCE);
                round.add("failure_reason", JsonNull.INSTANCE);
                round.add("validation_feedback", JsonNull.INSTANCE);
                round.add("data_revision_before", packet.get("data_revision"));
                JsonObject coverageBefore = TiDBMixedDataCoverage.snapshot(packet);
                long started = System.nanoTime();
                long timeBefore = budget("time_ms");
                long callsBefore = budget("model_calls");
                // Invalid program state is fatal; only a received proposal can be revised by the model.
                List<Map<String, String>> messages = TiDBDataPreparationProtocol.messages(packet.toString());
                JsonObject plan;
                boolean received = false;
                boolean recoverable = false;
                try {
                    String content = responder.respond(messages);
                    received = true;
                    round.addProperty("raw_response", content);
                    plan = TiDBDataPreparationProtocol.decision(content, packet);
                    round.add("decision", plan.deepCopy());
                    round.add("model_expectation", plan.get("expected_effect"));
                    if (plan.get("decision").getAsString().equals("append_rows")) {
                        rejectRepeatedFailure(plan);
                        verification(plan);
                    }
                    round.addProperty("validation_status", "accepted");
                } catch (Exception invalid) {
                    if (invalid instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    recoverable = received && invalid instanceof IllegalArgumentException
                            && !(invalid instanceof RepeatedFailedRow);
                    round.addProperty("validation_status", "rejected");
                    String message = received ? invalid.toString()
                            : "Model request failed: " + invalid.getClass().getSimpleName();
                    round.addProperty("failure_reason", message);
                    JsonObject feedback = new JsonObject();
                    feedback.addProperty("stage", received ? "plan_validation" : "model_request");
                    feedback.addProperty("code",
                            invalid instanceof TiDBDataPreparationProtocol.ValidationException
                                    ? ((TiDBDataPreparationProtocol.ValidationException) invalid).code
                                    : invalid instanceof RepeatedFailedRow ? "REPEATED_FAILED_ROW"
                                            : received ? "INVALID_PLAN" : "MODEL_REQUEST_FAILED");
                    feedback.addProperty("message",
                            received && invalid.getMessage() != null ? invalid.getMessage() : message);
                    feedback.addProperty("recoverable", recoverable);
                    feedback.addProperty("request_id", packet.get("request_id").getAsString());
                    feedback.add("data_revision", packet.get("data_revision"));
                    feedback.addProperty("execution_started", false);
                    feedback.addProperty("attempted_rows", 0);
                    feedback.addProperty("correction", "Use the latest state and remaining budget to submit a new "
                            + "observe, smaller/different append_rows, or stop; rejected operations were not executed");
                    round.add("validation_feedback", feedback);
                    round.add("decision", JsonNull.INSTANCE);
                    plan = null;
                }
                budget("model_calls", callsBefore - 1);
                long responseTime = (System.nanoTime() - started + 999_999) / 1_000_000;
                budget("time_ms", timeBefore - responseTime);
                if (plan != null && budget("time_ms") > 0) {
                    String action = plan.get("decision").getAsString();
                    if (action.equals("append_rows")) {
                        // The executor sees the budget advertised to this response, including the current call.
                        budget("model_calls", callsBefore);
                        append(plan, round);
                        budget("model_calls", callsBefore - 1);
                    } else if (action.equals("observe")) {
                        observe(plan.getAsJsonArray("observation_requests"), round);
                    }
                }
                long elapsed = (System.nanoTime() - started + 999_999) / 1_000_000;
                budget("time_ms", Math.min(budget("time_ms"), Math.max(0, timeBefore - elapsed)));
                if (!round.get("validation_feedback").isJsonNull()) {
                    JsonObject feedback = round.getAsJsonObject("validation_feedback");
                    feedback.add("remaining_budget", packet.getAsJsonObject("remaining_budget").deepCopy());
                    feedback.addProperty("retry_allowed",
                            recoverable && budget("model_calls") > 0 && budget("time_ms") > 0);
                }
                round.add("actual_observations", packet.get("observations").deepCopy());
                round.add("actual_samples", packet.get("samples").deepCopy());
                JsonObject coverageAfter = TiDBMixedDataCoverage.snapshot(packet);
                round.add("data_coverage_before", coverageBefore);
                round.add("data_coverage_after", coverageAfter);
                round.add("data_coverage_change", TiDBMixedDataCoverage.difference(coverageBefore, coverageAfter));
                JsonArray observationIds = new JsonArray();
                packet.getAsJsonArray("observations")
                        .forEach(entry -> observationIds.add(entry.getAsJsonObject().get("id")));
                round.add("observation_ids", observationIds);
                round.add("evaluation", evaluate(packet, uncertain ? List.of() : requirements));
                round.addProperty("decision_id", packet.get("request_id").getAsString());
                round.add("data_revision_after", uncertain ? JsonNull.INSTANCE : packet.get("data_revision"));
                rounds.add(round);
                JsonObject history = round.deepCopy();
                history.remove("state_before"); // Avoid recursive growth of history packets.
                packet.getAsJsonArray("history").add(history);
                if (plan == null) {
                    if (recoverable) {
                        continue;
                    }
                    termination = "response_rejected";
                    break;
                }
                if (uncertain || plan.get("decision").getAsString().equals("stop") || failedRounds >= failureLimit) {
                    termination = uncertain ? "requires_rebuild"
                            : plan.get("decision").getAsString().equals("stop") ? "model_stop" : "no_progress_limit";
                    break;
                }
            }
        } finally {
            executor.freeze();
            packet.addProperty("frozen", true);
        }
        JsonObject result = new JsonObject();
        result.addProperty("termination", termination);
        result.addProperty("requires_rebuild", uncertain);
        result.add("rounds", rounds.deepCopy());
        result.add("final_state", uncertain ? JsonNull.INSTANCE : packet.deepCopy());
        result.add("evaluation", evaluate(packet, uncertain ? List.of() : requirements));
        return result;
    }
}
