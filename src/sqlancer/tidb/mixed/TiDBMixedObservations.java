package sqlancer.tidb.mixed;

import java.math.BigInteger;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import sqlancer.tidb.TiDBSchema.TiDBColumn;
import sqlancer.tidb.TiDBSchema.TiDBTable;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation.TiDBComparisonOperator;
import sqlancer.tidb.ast.TiDBColumnReference;
import sqlancer.tidb.ast.TiDBExpression;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.ast.TiDBSelect;
import sqlancer.tidb.ast.TiDBTableReference;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;
import sqlancer.tidb.mixed.TiDBMixedReadOnly.Result;
import sqlancer.tidb.visitor.TiDBVisitor;

/** Catalog of bounded read-only capabilities, scoped to one immutable candidate and data revision. */
public final class TiDBMixedObservations {
    private final Candidate candidate;
    private final String queryId;
    private final long schemaRevision;
    private final long dataRevision;
    private final JsonObject queryDescription;
    private final Map<String, Probe> probes = new LinkedHashMap<>();
    private final TiDBMixedParameterizedObservations parameterized;
    public final List<Map<String, Object>> audit = new ArrayList<>();
    public final JsonArray observations = new JsonArray();
    public final JsonArray samples = new JsonArray();
    public String snapshot = "";
    private int sequence;

    public static final class Budget {
        public final int probes;
        public final int rows;
        public final long timeMs;

        public Budget(int probes, int rows, long timeMs) {
            if (probes < 0 || rows < 1 || rows >= Integer.MAX_VALUE || timeMs < 0) {
                throw new IllegalArgumentException("invalid observation budget");
            }
            this.probes = probes;
            this.rows = rows;
            this.timeMs = timeMs;
        }
    }

    static final class Probe {
        final String id;
        final String metric;
        final JsonObject target;
        final String description;
        final String sql;
        final String kind;
        final String table;
        final JsonObject parameters;
        final JsonArray bindings;

        Probe(String id, String metric, JsonObject target, String description, String sql, String kind, String table) {
            this(id, metric, target, description, sql, kind, table, new JsonObject(), new JsonArray());
        }

        Probe(String id, String metric, JsonObject target, String description, String sql, String kind, String table,
                JsonObject parameters, JsonArray bindings) {
            this.id = id;
            this.metric = metric;
            this.target = target;
            this.description = description;
            this.sql = sql;
            this.kind = kind;
            this.table = table;
            this.parameters = parameters.deepCopy();
            this.bindings = bindings.deepCopy();
        }
    }

    public TiDBMixedObservations(Candidate candidate, String queryId, long schemaRevision, long dataRevision) {
        if (schemaRevision < 0 || dataRevision < 0 || queryId.isBlank()) {
            throw new IllegalArgumentException("invalid observation identity");
        }
        this.candidate = candidate;
        this.queryId = queryId;
        this.schemaRevision = schemaRevision;
        this.dataRevision = dataRevision;
        queryDescription = describeQuery(candidate);
        plan();
        parameterized = new TiDBMixedParameterizedObservations(candidate, relation(null));
    }

    private static JsonArray tableNames(TiDBExpression expression) {
        JsonArray names = new JsonArray();
        if (expression instanceof TiDBTableReference) {
            names.add(((TiDBTableReference) expression).getTable().getName());
        } else if (expression instanceof TiDBJoin) {
            names.addAll(tableNames(((TiDBJoin) expression).getLeftTable()));
            names.addAll(tableNames(((TiDBJoin) expression).getRightTable()));
        }
        return names;
    }

    private static void describeJoin(TiDBExpression expression, JsonArray joins) {
        if (!(expression instanceof TiDBJoin)) {
            return;
        }
        TiDBJoin join = (TiDBJoin) expression;
        JsonObject object = new JsonObject();
        object.addProperty("id", "j" + joins.size());
        object.addProperty("type", join.getJoinType() == TiDBJoin.JoinType.NATURAL
                ? "NATURAL_" + join.getNaturalJoinType() : join.getJoinType().name());
        object.add("left_tables", tableNames(join.getLeftTable()));
        object.add("right_tables", tableNames(join.getRightTable()));
        object.addProperty("on",
                join.getOnCondition() == null ? null : TiDBVisitor.asStringDeterministic(join.getOnCondition()));
        joins.add(object);
        describeJoin(join.getLeftTable(), joins);
        describeJoin(join.getRightTable(), joins);
    }

    private static JsonObject describeQuery(Candidate candidate) {
        JsonObject query = new JsonObject();
        query.addProperty("sql", candidate.sql);
        JsonArray joins = new JsonArray();
        candidate.ast.getJoinList().forEach(expression -> describeJoin(expression, joins));
        query.add("joins", joins);
        query.addProperty("where", candidate.ast.getWhereClause() == null ? null
                : TiDBVisitor.asStringDeterministic(candidate.ast.getWhereClause()));
        JsonArray groups = new JsonArray();
        candidate.ast.getGroupByExpressions()
                .forEach(expression -> groups.add(TiDBVisitor.asStringDeterministic(expression)));
        query.add("group_by", groups);
        query.addProperty("having", candidate.ast.getHavingClause() == null ? null
                : TiDBVisitor.asStringDeterministic(candidate.ast.getHavingClause()));
        return query;
    }

    // Build the v1 preparation packet; scheduling and budget accounting are caller-owned.
    public JsonObject statePacket(String requestId, String caseId, JsonObject schema, JsonArray history,
            JsonObject remainingBudget) {
        JsonObject packet = versioned(queryId);
        packet.remove("id");
        packet.addProperty("protocol_version", TiDBDataPreparationProtocol.VERSION);
        packet.addProperty("request_id", requestId);
        packet.addProperty("case_id", caseId);
        packet.addProperty("phase", "DATA_PREPARATION");
        packet.addProperty("frozen", false);
        packet.addProperty("discrepancy_found", false);
        packet.add("query", queryDescription.deepCopy());
        packet.add("schema", schema.deepCopy());
        packet.add("observations", observations.deepCopy());
        packet.add("samples", samples.deepCopy());
        packet.add("history", history.deepCopy());
        packet.add("remaining_budget", remainingBudget.deepCopy());
        packet.add("available_observations", available());
        return TiDBDataPreparationProtocol.state(packet.toString());
    }

    private static String quote(String name) {
        return TiDBMixedSupport.quoteIdentifier(name);
    }

    private static String ref(TiDBColumn column) {
        return quote(column.getTable().getName()) + "." + quote(column.getName());
    }

    private void add(String id, String metric, String key, String target, String description, String sql, String kind,
            String table) {
        JsonObject object = new JsonObject();
        object.addProperty(key, target);
        if (probes.put(id, new Probe(id, metric, object, description, sql, kind, table)) != null) {
            throw new IllegalArgumentException("duplicate capability");
        }
    }

    private void count(String id, String metric, String key, String target, String description, String relation) {
        add(id, metric, key, target, description, relation == null ? null : "SELECT COUNT(*) AS count " + relation,
                "count", null);
    }

    private void plan() {
        for (TiDBTable table : candidate.tables) {
            String name = table.getName();
            count("rows_" + name, "table_row_count", "table", name, "Exact physical row count in TiKV snapshot",
                    "FROM " + quote(name));
            String projection = table.getColumns().stream().map(c -> quote(c.getName()))
                    .collect(Collectors.joining(","));
            add("sample_" + name, "table_sample", "table", name,
                    "Bounded table sample; complete only when the entire result is read",
                    "SELECT " + projection + " FROM " + quote(name), "sample", name);
            for (TiDBColumn col : table.getColumns()) {
                String qualified = name + "." + col.getName();
                count("nulls_" + qualified, "null_count", "column", qualified, "Exact NULL row count",
                        "FROM " + quote(name) + " WHERE " + quote(col.getName()) + " IS NULL");
                add("freq_" + qualified, "key_frequency", "column", qualified,
                        "Key frequencies including NULL; truncated lists are sampled, never full distributions",
                        "SELECT " + quote(col.getName()) + " AS key_value, COUNT(*) AS frequency FROM " + quote(name)
                                + " GROUP BY " + quote(col.getName()),
                        "rows", null);
            }
        }
        int i = 0;
        for (TiDBExpression expression : candidate.ast.getJoinList()) {
            i = joins(expression, i);
        }
        String before = relation(null);
        String after = relation(candidate.ast.getWhereClause());
        count("query_input_before", "query_input_rows_before_where", "query_id", queryId,
                "Rows emitted by original FROM/JOIN before WHERE and aggregation; outer rows included", before);
        count("query_input_after", "query_input_rows_after_where", "query_id", queryId,
                "Rows after original WHERE and before GROUP BY/HAVING; not a matching-pair count", after);
        add("query_empty", "query_result_empty", "query_id", queryId,
                "Whether the frozen original SELECT emits any row, including aggregation and HAVING", candidate.sql,
                "empty", null);
        groups(after);
        TiDBMixedQueryStages.plan(candidate.ast, before, (id, description, relation) -> count(id, "query_stage_count",
                "query_id", queryId, description, relation));
    }

    private int joins(TiDBExpression expression, int next) {
        if (!(expression instanceof TiDBJoin)) {
            return next;
        }
        TiDBJoin join = (TiDBJoin) expression;
        String id = "j" + next;
        boolean supported = join.getLeftTable() instanceof TiDBTableReference
                && join.getRightTable() instanceof TiDBTableReference
                && join.getOnCondition() instanceof TiDBBinaryComparisonOperation
                && join.getJoinType() != TiDBJoin.JoinType.NATURAL;
        TiDBColumn leftKey = null;
        TiDBColumn rightKey = null;
        if (supported) {
            TiDBBinaryComparisonOperation on = (TiDBBinaryComparisonOperation) join.getOnCondition();
            supported = on.getOp() == TiDBComparisonOperator.EQUALS && on.getLeft() instanceof TiDBColumnReference
                    && on.getRight() instanceof TiDBColumnReference;
            if (supported) {
                TiDBColumn a = ((TiDBColumnReference) on.getLeft()).getColumn();
                TiDBColumn b = ((TiDBColumnReference) on.getRight()).getColumn();
                TiDBTable left = ((TiDBTableReference) join.getLeftTable()).getTable();
                TiDBTable right = ((TiDBTableReference) join.getRightTable()).getTable();
                if (a.getTable() == right && b.getTable() == left) {
                    TiDBColumn swap = a;
                    a = b;
                    b = swap;
                }
                supported = a.getTable() == left && b.getTable() == right && left != right;
                leftKey = a;
                rightKey = b;
            }
        }
        joinCounts(id, join, supported ? leftKey : null, supported ? rightKey : null);
        int afterLeft = joins(join.getLeftTable(), next + 1);
        return joins(join.getRightTable(), afterLeft);
    }

    private void joinCounts(String id, TiDBJoin join, TiDBColumn left, TiDBColumn right) {
        String l = left == null ? "" : quote(left.getTable().getName());
        String r = right == null ? "" : quote(right.getTable().getName());
        String on = left == null ? "" : ref(left) + "=" + ref(right);
        String inner = "FROM " + l + " INNER JOIN " + r + " ON " + on;
        String leftUnmatched = "FROM " + l + " WHERE NOT EXISTS (SELECT 1 FROM " + r + " WHERE " + on + ")";
        String rightUnmatched = "FROM " + r + " WHERE NOT EXISTS (SELECT 1 FROM " + l + " WHERE " + on + ")";
        String reason = left == null ? "Unsupported: requires a single = between two distinct base-table columns"
                : "Exact local JOIN statistic; independent of other FROM items";
        count(id + "_pairs_before", "join_pairs_before_where", "join_id", id, reason, left == null ? null : inner);
        count(id + "_left_unmatched", "left_unmatched_input_rows", "join_id", id, reason,
                left == null ? null : leftUnmatched);
        count(id + "_right_unmatched", "right_unmatched_input_rows", "join_id", id, reason,
                left == null ? null : rightUnmatched);
        count(id + "_left_repeated", "left_input_rows_with_multiple_matches", "join_id", id, reason,
                left == null ? null : "FROM " + l + " WHERE (SELECT COUNT(*) FROM " + r + " WHERE " + on + ")>1");
        count(id + "_right_repeated", "right_input_rows_with_multiple_matches", "join_id", id, reason,
                left == null ? null : "FROM " + r + " WHERE (SELECT COUNT(*) FROM " + l + " WHERE " + on + ")>1");
        // WHERE may reference other tables. Only attach it to the isolated pair when it is the entire FROM.
        boolean wholePair = left != null && candidate.tables.size() == 2 && candidate.ast.getFromList().isEmpty()
                && candidate.ast.getJoinList().size() == 1 && candidate.ast.getJoinList().get(0) == join;
        String filter = candidate.ast.getWhereClause() == null ? ""
                : " WHERE " + TiDBVisitor.asStringDeterministic(candidate.ast.getWhereClause());
        String after = wholePair ? inner + filter : null;
        String scope = wholePair ? "Exact matching-pair count after original WHERE; excludes null-extended rows"
                : "Unsupported: WHERE-scoped JOIN metrics require the JOIN to be the entire two-table FROM";
        count(id + "_pairs_after", "join_pairs_after_where", "join_id", id, scope, after);
        String key = left == null ? "" : ref(left);
        count(id + "_duplicate_after", "duplicate_keys_after_where", "join_id", id,
                scope + "; different non-NULL keys with at least two surviving matching pairs",
                after == null ? null : "FROM (SELECT COUNT(*) AS pairs " + after + " GROUP BY " + key
                        + " HAVING COUNT(*)>1) AS ms_duplicates");
        for (boolean leftSide : List.of(true, false)) {
            boolean preserved = leftSide ? join.getJoinType() == TiDBJoin.JoinType.LEFT
                    : join.getJoinType() == TiDBJoin.JoinType.RIGHT;
            TiDBColumn missingColumn = leftSide ? right : left;
            String missingKey = missingColumn == null ? "" : ref(missingColumn);
            String outer = "FROM " + l + (leftSide ? " LEFT JOIN " : " RIGHT JOIN ") + r + " ON " + on;
            String where = " WHERE " + missingKey + " IS NULL";
            if (candidate.ast.getWhereClause() != null) {
                where += " AND (" + TiDBVisitor.asStringDeterministic(candidate.ast.getWhereClause()) + ")";
            }
            String unmatched = preserved ? outer + where : inner + " WHERE FALSE";
            count(id + (leftSide ? "_left_unmatched_after" : "_right_unmatched_after"),
                    leftSide ? "left_unmatched_output_rows_after_where" : "right_unmatched_output_rows_after_where",
                    "join_id", id, scope + "; only the side preserved by original JOIN can emit unmatched rows",
                    wholePair ? unmatched : null);
        }
    }

    private String relation(TiDBExpression where) {
        TiDBSelect select = new TiDBSelect();
        select.setFetchColumns(List.of(sqlancer.tidb.ast.TiDBConstant.createIntConstant(1)));
        select.setFromList(candidate.ast.getFromList());
        select.setJoinList(candidate.ast.getJoinList());
        select.setWhereClause(where);
        String rendered = TiDBVisitor.asStringDeterministic(select);
        return rendered.substring("SELECT 1 ".length());
    }

    private void groups(String relation) {
        List<TiDBExpression> keys = candidate.ast.getGroupByExpressions();
        boolean supported = !keys.isEmpty() && keys.stream().allMatch(TiDBColumnReference.class::isInstance);
        String description = supported ? "Explicit column GROUP BY; counts are separate from pre-group input rows"
                : "Unsupported/unverified: requires explicit column GROUP BY (implicit aggregate is not inferred)";
        String group = " GROUP BY "
                + keys.stream().map(TiDBVisitor::asStringDeterministic).collect(Collectors.joining(","));
        String grouped = "SELECT COUNT(*) AS group_size " + relation + group;
        List<String> projectedKeys = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            projectedKeys.add(TiDBVisitor.asStringDeterministic(keys.get(i)) + " AS group_key_" + i);
        }
        add("group_sizes", "group_sizes_before_having", "query_id", queryId, description, supported
                ? "SELECT " + String.join(",", projectedKeys) + ", COUNT(*) AS group_size " + relation + group : null,
                "rows", null);
        add("groups_before_having", "group_summary_before_having", "query_id", queryId, description,
                supported ? summary(grouped) : null, "summary", null);
        String having = candidate.ast.getHavingClause() == null ? ""
                : " HAVING " + TiDBVisitor.asStringDeterministic(candidate.ast.getHavingClause());
        add("groups_after_having", "group_summary_after_having", "query_id", queryId,
                description + "; surviving groups and their input rows, before projection",
                supported ? summary(grouped + having) : null, "summary", null);
    }

    private static String summary(String grouped) {
        return "SELECT COUNT(*) AS group_count, COALESCE(SUM(group_size),0) AS input_rows,"
                + " COALESCE(MIN(group_size),0) AS min_size, COALESCE(MAX(group_size),0) AS max_size FROM (" + grouped
                + ") AS ms_groups";
    }

    public JsonArray available() {
        JsonArray result = new JsonArray();
        for (Probe probe : probes.values()) {
            if (probe.sql != null) {
                JsonObject capability = new JsonObject();
                capability.addProperty("id", probe.id);
                capability.addProperty("metric", probe.metric);
                capability.add("target", probe.target.deepCopy());
                capability.addProperty("description", probe.description);
                capability.add("goal_fields", TiDBMixedPreparationGoals.fields(probe.kind));
                result.add(capability);
            }
        }
        result.addAll(parameterized.available());
        return result;
    }

    public List<String> initialIds() {
        List<String> ids = new ArrayList<>();
        for (TiDBTable table : candidate.tables) {
            ids.add("rows_" + table.getName());
            ids.add("sample_" + table.getName());
        }
        ids.add("query_empty");
        return ids;
    }

    public JsonArray assessmentRequests(JsonArray goals) {
        Map<String, JsonElement> requests = new LinkedHashMap<>();
        // Preserve model-selected parameters in the independent final assessment, within the same read budget.
        for (JsonElement goal : goals) {
            JsonElement request = TiDBMixedObservationRequest.goalRequest(goal.getAsJsonObject());
            requests.put(TiDBMixedObservationRequest.key(request), request);
        }
        for (String id : allIds()) {
            JsonElement request = new com.google.gson.JsonPrimitive(id);
            requests.putIfAbsent(TiDBMixedObservationRequest.key(request), request);
        }
        JsonArray result = new JsonArray();
        requests.values().forEach(request -> result.add(request.deepCopy()));
        return result;
    }

    public void bootstrap(TiDBMixedSupport io, JsonObject budget) throws SQLException {
        long started = System.nanoTime();
        List<String> ids = initialIds();
        int requested = (int) Math.min(ids.size(), budget.get("probe_requests").getAsLong());
        collect(io, ids.subList(0, requested),
                new Budget(requested, 2, Math.min(10_000, budget.get("time_ms").getAsLong())));
        budget.addProperty("probe_requests", budget.get("probe_requests").getAsLong() - requested);
        budget.addProperty("time_ms",
                Math.max(0, budget.get("time_ms").getAsLong() - (System.nanoTime() - started + 999_999) / 1_000_000));
    }

    public List<String> allIds() {
        // Give query/relationship facts a chance before spending the bounded budget on many distributions.
        List<String> ids = new ArrayList<>(probes.keySet());
        ids.sort(Comparator.comparingInt(id -> priority(probes.get(id).metric)));
        return ids;
    }

    private static int priority(String metric) {
        if (metric.startsWith("query_")) {
            return 0;
        }
        if (metric.equals("table_row_count")) {
            return 1;
        }
        if (metric.startsWith("join_") || metric.contains("match") || metric.equals("duplicate_keys_after_where")) {
            return 2;
        }
        if (metric.startsWith("group_")) {
            return 3;
        }
        if (metric.equals("null_count")) {
            return 4;
        }
        return metric.equals("table_sample") ? 5 : 6;
    }

    private JsonObject versioned(String id) {
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("query_id", queryId);
        result.addProperty("schema_revision", schemaRevision);
        result.addProperty("data_revision", dataRevision);
        return result;
    }

    private void record(Probe probe, String status, JsonObject value, String error, Map<String, Object> evidence) {
        JsonObject observation = versioned("obs_" + dataRevision + "_" + sequence++);
        observation.addProperty("capability_id", probe.id);
        if (!probe.parameters.isEmpty()) {
            observation.add("parameters", probe.parameters.deepCopy());
        }
        observation.addProperty("status", status);
        observation.add("value", value == null ? JsonNull.INSTANCE : value);
        observation.addProperty("error", error);
        observations.add(observation);
        evidence.put("observation_id", observation.get("id").getAsString());
        evidence.put("metric", probe.metric);
        evidence.put("target", probe.target);
        evidence.put("description", probe.description);
        evidence.put("snapshot", snapshot);
        if (!probe.parameters.isEmpty()) {
            evidence.put("parameters", probe.parameters.deepCopy());
            evidence.put("bindings", probe.bindings.deepCopy());
        }
        audit.add(evidence);
    }

    private void execute(TiDBMixedSupport io, Probe probe, int rows, int timeout) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sql", probe.sql);
        try {
            JsonObject value = new JsonObject();
            if (probe.kind.equals("empty")) {
                value.addProperty("empty", TiDBMixedReadOnly.isEmpty(io, probe.sql, probe.bindings, timeout));
                record(probe, "exact", value, null, evidence);
                return;
            }
            Result result = TiDBMixedReadOnly.read(io, probe.sql, probe.bindings, rows, timeout);
            String status = result.complete ? "exact" : "sampled";
            if (probe.kind.equals("count")) {
                if (!result.complete || result.rows.size() != 1) {
                    throw new IllegalArgumentException("count result must be one complete row");
                }
                String count = result.rows.get(0).getAsJsonArray().get(0).getAsJsonObject().get("value").getAsString();
                if (new BigInteger(count).signum() < 0) {
                    throw new IllegalArgumentException("negative count");
                }
                value.addProperty("count", count);
            } else {
                JsonArray names = new JsonArray();
                result.columns.forEach(names::add);
                value.add("columns", names);
                value.add("rows", result.rows);
                value.addProperty("complete", result.complete);
                if (probe.kind.equals("sample")) {
                    JsonObject sample = versioned("sample_" + dataRevision + "_" + sequence++);
                    sample.addProperty("table", probe.table);
                    sample.add("columns", names.deepCopy());
                    sample.add("rows", result.rows.deepCopy());
                    sample.addProperty("complete", result.complete);
                    samples.add(sample);
                }
            }
            record(probe, status, value, null, evidence);
        } catch (SQLException | IllegalArgumentException error) {
            if (error instanceof SQLException) {
                evidence.put("sqlstate", ((SQLException) error).getSQLState());
                evidence.put("error_code", ((SQLException) error).getErrorCode());
            }
            record(probe, "error", null, error.toString(), evidence);
        }
    }

    // All reads share one TiKV TSO. Restore session state even after an error.
    public void collect(TiDBMixedSupport io, List<String> requested, Budget budget) throws SQLException {
        JsonArray requests = new JsonArray();
        requested.forEach(requests::add);
        collectRequests(io, requests, budget);
    }

    public void collectRequests(TiDBMixedSupport io, JsonArray requests, Budget budget) throws SQLException {
        JsonObject catalog = new JsonObject();
        catalog.add("available_observations", available());
        // Static allIds includes explicit unsupported entries, which are audited without issuing SQL.
        java.util.Set<String> keys = new java.util.HashSet<>();
        List<Probe> selected = new ArrayList<>();
        for (JsonElement request : requests) {
            if (!keys.add(TiDBMixedObservationRequest.key(request))) {
                throw new IllegalArgumentException("duplicate observation request");
            }
            String id = TiDBMixedObservationRequest.capabilityId(request);
            if (probes.containsKey(id)) {
                if (request.isJsonObject()) {
                    throw new IllegalArgumentException("static probe does not accept parameters");
                }
                selected.add(probes.get(id));
            } else {
                JsonArray single = new JsonArray();
                single.add(request);
                TiDBMixedObservationRequest.validate(catalog, single, 1);
                selected.add(parameterized.compile(request));
            }
        }
        Map<String, String> previous = io
                .textRows("SELECT @@tidb_snapshot AS snapshot," + " @@tidb_isolation_read_engines AS engines").get(0);
        String oldSnapshot = io.snapshot;
        long deadline = System.nanoTime() + Math.min(budget.timeMs, 3_600_000L) * 1_000_000L;
        int spent = 0;
        try {
            io.exec("SET SESSION tidb_isolation_read_engines='tikv'");
            if (previous.get("snapshot") == null || previous.get("snapshot").isEmpty()
                    || previous.get("snapshot").equals("0")) {
                io.beginSnapshot();
                snapshot = io.snapshot;
            } else {
                snapshot = previous.get("snapshot");
                io.verifySnapshot(snapshot);
            }
            for (Probe probe : selected) {
                long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                if (probe.sql == null) {
                    record(probe, "unsupported", null, probe.description, new LinkedHashMap<>());
                } else if (spent >= budget.probes || remaining < 1000) {
                    record(probe, "unknown", null, "Not measured: probe or time budget exhausted",
                            new LinkedHashMap<>());
                } else {
                    spent++;
                    execute(io, probe, budget.rows, (int) Math.min(30, remaining / 1000));
                }
            }
        } finally {
            try {
                io.exec("SET SESSION tidb_snapshot="
                        + TiDBMixedSupport.literal(previous.get("snapshot") == null ? "" : previous.get("snapshot")));
            } finally {
                io.snapshot = oldSnapshot;
                io.exec("SET SESSION tidb_isolation_read_engines=" + TiDBMixedSupport.literal(previous.get("engines")));
            }
        }
    }
}
