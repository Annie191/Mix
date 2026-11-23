package sqlancer.tidb.mixed;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.TiDBSchema.TiDBTable;
import sqlancer.tidb.gen.TiDBInsertGenerator;
import sqlancer.tidb.gen.TiDBInsertGenerator.AppendRow;
import sqlancer.tidb.mixed.TiDBMixedReadOnly.Result;

/** Controlled append boundary. Accepts the v1 JSON contract, never model-provided SQL. No AI scheduling. */
public final class TiDBMixedAppendExecutor {
    private final TiDBGlobalState state;
    private final List<TiDBTable> tables;
    private final JsonObject initial;
    private final TiDBMixedSupport io;
    private final int readLimit;
    private final Set<String> requests = new HashSet<>();
    private long revision;
    private long rowsRemaining;
    private long timeRemaining;
    private boolean frozen;
    private boolean blocked;

    public TiDBMixedAppendExecutor(TiDBGlobalState state, JsonObject packet, int readLimit) {
        initial = TiDBDataPreparationProtocol.state(packet.toString());
        if (readLimit < 1 || readLimit >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("invalid append context");
        }
        this.state = state;
        tables = new ArrayList<>();
        for (JsonElement table : initial.getAsJsonObject("schema").getAsJsonArray("tables")) {
            String name = table.getAsJsonObject().get("name").getAsString();
            tables.add(
                    state.getSchema().getDatabaseTables().stream().filter(t -> t.getName().equals(name) && !t.isView())
                            .findFirst().orElseThrow(() -> new IllegalArgumentException("unknown append base table")));
        }
        this.io = new TiDBMixedSupport(state);
        this.readLimit = readLimit;
        revision = initial.get("data_revision").getAsLong();
        rowsRemaining = initial.getAsJsonObject("remaining_budget").get("insert_rows").getAsLong();
        timeRemaining = initial.getAsJsonObject("remaining_budget").get("time_ms").getAsLong();
        frozen = initial.get("frozen").getAsBoolean() || initial.get("discrepancy_found").getAsBoolean();
    }

    public synchronized void freeze() {
        frozen = true;
    }

    public synchronized long remainingRows() {
        return rowsRemaining;
    }

    public synchronized long dataRevision() {
        if (blocked) {
            throw new IllegalStateException("append outcome/session uncertain; rebuild before reusing data");
        }
        return revision;
    }

    private static long rowCount(JsonObject plan) {
        long count = 0;
        for (JsonElement operation : plan.getAsJsonArray("operations")) {
            count += operation.getAsJsonObject().getAsJsonArray("rows").size();
        }
        return count;
    }

    private JsonObject validate(String json, JsonObject input) {
        JsonObject packet = TiDBDataPreparationProtocol.state(input.toString());
        if (blocked || frozen || packet.get("data_revision").getAsLong() != revision) {
            throw new IllegalArgumentException("stale, frozen, or blocked append context");
        }
        for (String key : List.of("case_id", "query_id", "schema_revision", "query", "schema")) {
            if (!initial.get(key).equals(packet.get(key))) {
                throw new IllegalArgumentException("append context changed: " + key);
            }
        }
        JsonObject plan = TiDBDataPreparationProtocol.decision(json, packet);
        if (!"append_rows".equals(plan.get("decision").getAsString()) || rowCount(plan) > rowsRemaining
                || revision > Long.MAX_VALUE - rowCount(plan) || timeRemaining < 1000
                || requests.contains(plan.get("request_id").getAsString())) {
            throw new IllegalArgumentException("append action, budget, or request rejected");
        }
        return plan;
    }

    private Map<String, String> session() throws SQLException {
        Map<String, String> session = io.textRows("SELECT DATABASE() AS db, @@autocommit AS autocommit,"
                + " @@tidb_current_ts AS transaction_ts, @@tidb_snapshot AS snapshot,"
                + " @@tidb_isolation_read_engines AS engines").get(0);
        if (!state.getDatabaseName().equals(session.get("db")) || !"1".equals(session.get("autocommit"))
                || !"0".equals(session.get("transaction_ts")) || !"".equals(session.get("snapshot"))) {
            throw new IllegalArgumentException(
                    "append requires the expected database, no snapshot, and no active transaction");
        }
        return session;
    }

    private void actualSchema() {
        TiDBMixedMetadata actual = TiDBMixedMetadata.collect(io, state.getDatabaseName(), tables,
                initial.get("schema_revision").getAsLong());
        if (!actual.schema.get("complete").getAsBoolean() || !actual.schema.equals(initial.getAsJsonObject("schema"))) {
            throw new IllegalArgumentException("actual schema incomplete or changed; refresh the state packet");
        }
        for (int i = 0; i < tables.size(); i++) {
            JsonArray columns = actual.schema.getAsJsonArray("tables").get(i).getAsJsonObject()
                    .getAsJsonArray("columns");
            if (columns.size() != tables.get(i).getColumns().size()) {
                throw new IllegalArgumentException("SQLancer column metadata is stale");
            }
            for (int c = 0; c < columns.size(); c++) {
                JsonObject column = columns.get(c).getAsJsonObject();
                sqlancer.tidb.TiDBSchema.TiDBColumn current = tables.get(i).getColumns().get(c);
                if (!column.get("name").getAsString().equals(current.getName())
                        || column.get("generated").getAsBoolean() != current.isGenerated()
                        || column.get("auto_increment").getAsBoolean() != current.isAutoIncrement()) {
                    throw new IllegalArgumentException("SQLancer writable-column metadata is stale");
                }
            }
        }
    }

    private static Object decode(JsonObject cell) {
        JsonElement value = cell.get("value");
        switch (cell.get("type").getAsString()) {
        case "integer":
            return new BigInteger(value.getAsString());
        case "decimal":
            return new BigDecimal(value.getAsString());
        case "binary":
            return Base64.getDecoder().decode(value.getAsString());
        case "boolean":
            return value.getAsBoolean();
        case "null":
            return null;
        case "text":
            return value.getAsString();
        default:
            throw new IllegalArgumentException("unsupported append value");
        }
    }

    private static JsonObject encode(Object value) {
        JsonObject cell = new JsonObject();
        if (value == null) {
            cell.addProperty("type", "null");
            cell.add("value", JsonNull.INSTANCE);
        } else if (value instanceof byte[]) {
            cell.addProperty("type", "binary");
            cell.addProperty("value", Base64.getEncoder().encodeToString((byte[]) value));
        } else if (value instanceof Boolean) {
            cell.addProperty("type", "boolean");
            cell.addProperty("value", (Boolean) value);
        } else {
            cell.addProperty("type", value instanceof BigDecimal || value instanceof Double ? "decimal"
                    : value instanceof Number ? "integer" : "text");
            cell.addProperty("value", value.toString());
        }
        return cell;
    }

    private static void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof BigInteger) {
                statement.setBigDecimal(i + 1, new BigDecimal((BigInteger) value));
            } else if (value instanceof BigDecimal) {
                statement.setBigDecimal(i + 1, (BigDecimal) value);
            } else if (value instanceof byte[]) {
                statement.setBytes(i + 1, (byte[]) value);
            } else {
                statement.setObject(i + 1, value);
            }
        }
    }

    private Result readTable(TiDBTable table, int timeout) throws SQLException {
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < table.getColumns().size(); i++) {
            columns.add(TiDBMixedSupport.quoteIdentifier(table.getColumns().get(i).getName()) + " AS ms_c" + i);
        }
        Result result = TiDBMixedReadOnly.read(io,
                "SELECT " + String.join(",", columns) + " FROM " + TiDBMixedSupport.quoteIdentifier(table.getName()),
                readLimit, timeout);
        if (!result.complete) {
            throw new IllegalArgumentException("table exceeds exact change verification row limit");
        }
        return result;
    }

    private static JsonArray addedRow(Result before, Result after) {
        Map<String, Integer> counts = new HashMap<>();
        before.rows.forEach(row -> counts.merge(row.toString(), 1, Integer::sum));
        JsonArray added = null;
        for (JsonElement row : after.rows) {
            String key = row.toString();
            int count = counts.getOrDefault(key, 0);
            if (count > 0) {
                counts.put(key, count - 1);
            } else if (added == null) {
                added = row.getAsJsonArray();
            } else {
                throw new IllegalArgumentException("insert changed more than one row");
            }
        }
        if (added == null || counts.values().stream().anyMatch(count -> count != 0)) {
            throw new IllegalArgumentException("insert did not only add one row");
        }
        return added;
    }

    private static boolean sameValue(JsonObject expected, JsonObject actual) {
        String type = expected.get("type").getAsString();
        if (type.equals("null") || actual.get("type").getAsString().equals("null")) {
            return type.equals(actual.get("type").getAsString());
        }
        if (type.equals("integer") || type.equals("decimal")) {
            if (!List.of("integer", "decimal").contains(actual.get("type").getAsString())) {
                return false;
            }
            return new BigDecimal(expected.get("value").getAsString())
                    .compareTo(new BigDecimal(actual.get("value").getAsString())) == 0;
        }
        return expected.equals(actual);
    }

    private static JsonObject actualValues(TiDBTable table, JsonArray row, JsonObject specified) {
        JsonObject actual = new JsonObject();
        for (int i = 0; i < table.getColumns().size(); i++) {
            String name = table.getColumns().get(i).getName();
            JsonObject value = row.get(i).getAsJsonObject();
            actual.add(name, value.deepCopy());
            if (specified.has(name) && !sameValue(specified.getAsJsonObject(name), value)) {
                throw new IllegalArgumentException("specified value changed on write: " + name);
            }
        }
        return actual;
    }

    private static void error(JsonObject write, Exception error) {
        write.addProperty("error", error.toString());
        if (error instanceof SQLException) {
            write.addProperty("sqlstate", ((SQLException) error).getSQLState());
            write.addProperty("error_code", ((SQLException) error).getErrorCode());
        }
    }

    private static JsonObject newWrite(String table, int index, JsonObject specified) {
        JsonObject write = new JsonObject();
        write.addProperty("table", table);
        write.addProperty("row_index", index);
        write.addProperty("status", "failed");
        write.addProperty("affected_rows", 0);
        write.add("specified_values", specified.deepCopy());
        for (String key : List.of("attempted_values", "actual_values", "sql", "sqlstate", "error_code", "error")) {
            write.add(key, JsonNull.INSTANCE);
        }
        return write;
    }

    private void insert(AppendRow append, int timeout) throws SQLException {
        try (PreparedStatement statement = (PreparedStatement) state.getConnection().prepareStatement(append.sql)) {
            statement.setQueryTimeout(timeout);
            bind(statement, append.values);
            io.events.add(Map.of("stage", "APPEND_ROW", "sql", append.sql));
            int affected = statement.executeUpdate();
            SQLWarning warning = statement.getWarnings();
            if (warning != null) {
                throw new SQLException("append warning: " + warning.getMessage(), warning.getSQLState(),
                        warning.getErrorCode());
            }
            if (affected != 1) {
                throw new IllegalArgumentException("ordinary one-row INSERT must affect exactly one row");
            }
        }
    }

    private JsonObject write(TiDBTable table, int index, JsonObject specified, int timeout) {
        JsonObject write = newWrite(table.getName(), index, specified);
        boolean transaction = false;
        boolean committing = false;
        try {
            Map<String, Object> values = new LinkedHashMap<>();
            specified.entrySet()
                    .forEach(entry -> values.put(entry.getKey(), decode(entry.getValue().getAsJsonObject())));
            AppendRow append = TiDBInsertGenerator.appendRow(state, table, values);
            JsonObject attempted = new JsonObject();
            for (int i = 0; i < append.columns.size(); i++) {
                attempted.add(append.columns.get(i).getName(), encode(append.values.get(i)));
            }
            write.add("attempted_values", attempted);
            write.addProperty("sql", append.sql);
            transaction = true;
            io.exec("BEGIN");
            Result before = readTable(table, timeout);
            insert(append, timeout);
            Result after = readTable(table, timeout);
            JsonObject actual = actualValues(table, addedRow(before, after), specified);
            committing = true;
            io.exec("COMMIT");
            transaction = false;
            revision++;
            write.addProperty("status", "success");
            write.addProperty("affected_rows", 1);
            write.add("actual_values", actual);
        } catch (SQLException | TiDBMixedSupport.Rejected | IllegalArgumentException failure) {
            error(write, failure);
            if (committing) {
                blocked = true;
                write.addProperty("status", "unknown");
                write.add("affected_rows", JsonNull.INSTANCE);
            }
        } finally {
            if (transaction) {
                try {
                    io.exec("ROLLBACK");
                } catch (SQLException | TiDBMixedSupport.Rejected failure) {
                    blocked = true;
                    write.addProperty("status", "unknown");
                    write.add("affected_rows", JsonNull.INSTANCE);
                    write.addProperty("rollback_error", failure.toString());
                }
            }
        }
        write.add("data_revision_after", blocked ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(revision));
        return write;
    }

    // The caller supplies a current packet; persistent local limits prevent budget/revision resets between requests.
    public synchronized JsonObject execute(String planJson, JsonObject input) throws SQLException {
        JsonObject packet = TiDBDataPreparationProtocol.state(input.toString());
        JsonObject plan = validate(planJson, packet);
        Map<String, String> previous = session();
        actualSchema(); // Recheck the real schema before any row; never trust generated-column claims in the plan.
        requests.add(plan.get("request_id").getAsString());
        long started = System.nanoTime();
        long allowed = Math.min(3_600_000,
                Math.min(timeRemaining, packet.getAsJsonObject("remaining_budget").get("time_ms").getAsLong()));
        long deadline = started + allowed * 1_000_000;
        JsonObject feedback = new JsonObject();
        feedback.addProperty("decision_id", plan.get("request_id").getAsString());
        feedback.add("decision", plan.deepCopy());
        feedback.addProperty("validation_status", "accepted");
        feedback.addProperty("data_revision_before", revision);
        JsonArray writes = new JsonArray();
        feedback.add("writes", writes);
        feedback.add("observation_ids", new JsonArray());
        feedback.add("failure_reason", JsonNull.INSTANCE);
        int attempted = 0;
        try {
            io.exec("SET SESSION tidb_isolation_read_engines='tikv'");
            Map<String, TiDBTable> byName = tables.stream().collect(Collectors.toMap(TiDBTable::getName, t -> t));
            for (JsonElement operation : plan.getAsJsonArray("operations")) {
                JsonObject object = operation.getAsJsonObject();
                TiDBTable table = byName.get(object.get("table").getAsString());
                JsonArray rows = object.getAsJsonArray("rows");
                for (int i = 0; i < rows.size(); i++) {
                    long remaining = (deadline - System.nanoTime()) / 1_000_000;
                    if (blocked || remaining < 1000 || rowsRemaining == 0) {
                        JsonObject skipped = newWrite(table.getName(), i, rows.get(i).getAsJsonObject());
                        skipped.addProperty("status", "not_attempted");
                        skipped.add("data_revision_after",
                                blocked ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(revision));
                        skipped.addProperty("error", "Execution stopped: uncertain outcome, time, or row budget");
                        writes.add(skipped);
                        continue;
                    }
                    rowsRemaining--;
                    attempted++;
                    writes.add(write(table, i, rows.get(i).getAsJsonObject(), (int) Math.min(30, remaining / 1000)));
                }
            }
        } catch (SQLException | TiDBMixedSupport.Rejected failure) {
            feedback.addProperty("failure_reason", failure.toString());
            // Session setup failed before the row loop; report every row without spending its budget.
            for (JsonElement operation : plan.getAsJsonArray("operations")) {
                JsonObject object = operation.getAsJsonObject();
                JsonArray rows = object.getAsJsonArray("rows");
                for (int i = 0; i < rows.size(); i++) {
                    JsonObject skipped = newWrite(object.get("table").getAsString(), i, rows.get(i).getAsJsonObject());
                    skipped.addProperty("status", "not_attempted");
                    skipped.addProperty("data_revision_after", revision);
                    error(skipped, failure);
                    writes.add(skipped);
                }
            }
        } finally {
            timeRemaining = Math.max(0, timeRemaining - (System.nanoTime() - started + 999_999) / 1_000_000);
            try {
                io.exec("SET SESSION tidb_isolation_read_engines=" + TiDBMixedSupport.literal(previous.get("engines")));
            } catch (SQLException | TiDBMixedSupport.Rejected failure) {
                blocked = true;
                feedback.addProperty("session_error", failure.toString());
            }
        }
        feedback.add("data_revision_after", blocked ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(revision));
        feedback.addProperty("attempted_rows", attempted);
        feedback.addProperty("remaining_insert_rows", rowsRemaining);
        feedback.addProperty("remaining_time_ms", timeRemaining);
        feedback.addProperty("requires_rebuild", blocked);
        if (writes.asList().stream()
                .anyMatch(row -> !"success".equals(row.getAsJsonObject().get("status").getAsString()))) {
            feedback.addProperty("failure_reason",
                    "Some rows failed, were not attempted, or have an unknown commit outcome");
        }
        feedback.add("events", new com.google.gson.Gson().toJsonTree(io.events));
        return feedback;
    }
}
