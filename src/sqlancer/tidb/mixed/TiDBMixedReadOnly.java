package sqlancer.tidb.mixed;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/** Bounded, streaming reads of program-built SELECT statements. No model-provided SQL enters this boundary. */
final class TiDBMixedReadOnly {
    static final class Result {
        final List<String> columns = new ArrayList<>();
        final JsonArray rows = new JsonArray();
        boolean complete = true;
    }

    private TiDBMixedReadOnly() {
    }

    @FunctionalInterface
    private interface ResultReader<T> {
        T read(ResultSet rows) throws SQLException;
    }

    private static JsonObject value(ResultSet rows, ResultSetMetaData metadata, int index) throws SQLException {
        JsonObject cell = new JsonObject();
        String text = rows.getString(index);
        if (text == null) {
            cell.addProperty("type", "null");
            cell.add("value", JsonNull.INSTANCE);
            return cell;
        }
        switch (metadata.getColumnType(index)) {
        case Types.TINYINT:
        case Types.SMALLINT:
        case Types.INTEGER:
        case Types.BIGINT:
            cell.addProperty("type", "integer");
            // MySQL/TiDB ZEROFILL getString() can include leading zeroes; the protocol uses canonical integers.
            cell.addProperty("value", new BigInteger(text).toString());
            break;
        case Types.DECIMAL:
        case Types.NUMERIC:
        case Types.FLOAT:
        case Types.REAL:
        case Types.DOUBLE:
            cell.addProperty("type", "decimal");
            cell.addProperty("value", new BigDecimal(text).toPlainString());
            break;
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        case Types.BLOB:
        case Types.BIT:
            cell.addProperty("type", "binary");
            cell.addProperty("value", Base64.getEncoder().encodeToString(rows.getBytes(index)));
            break;
        case Types.BOOLEAN:
            cell.addProperty("type", "boolean");
            cell.addProperty("value", rows.getBoolean(index));
            break;
        default:
            cell.addProperty("type", "text");
            cell.addProperty("value", text);
            break;
        }
        return cell;
    }

    static Result read(TiDBMixedSupport io, String sql, int rowLimit, int timeoutSeconds) throws SQLException {
        return read(io, sql, new JsonArray(), rowLimit, timeoutSeconds);
    }

    static Result read(TiDBMixedSupport io, String sql, JsonArray bindings, int rowLimit, int timeoutSeconds)
            throws SQLException {
        return read(io, sql, bindings, rowLimit, timeoutSeconds, rows -> {
            Result result = new Result();
            ResultSetMetaData metadata = rows.getMetaData();
            int columns = metadata.getColumnCount();
            for (int i = 1; i <= columns; i++) {
                String label = metadata.getColumnLabel(i);
                result.columns.add(label == null || label.isBlank() ? "column_" + i : label.toLowerCase(Locale.ROOT));
            }
            while (rows.next()) {
                if (result.rows.size() == rowLimit) {
                    result.complete = false;
                    break;
                }
                JsonArray row = new JsonArray();
                for (int i = 1; i <= columns; i++) {
                    row.add(value(rows, metadata, i));
                }
                result.rows.add(row);
            }
            return result;
        });
    }

    static boolean isEmpty(TiDBMixedSupport io, String sql, JsonArray bindings, int timeoutSeconds)
            throws SQLException {
        // Existence is exact after one next(); labels, types and cell values are irrelevant.
        return read(io, sql, bindings, 1, timeoutSeconds, rows -> !rows.next());
    }

    private static <T> T read(TiDBMixedSupport io, String sql, JsonArray bindings, int rowLimit, int timeoutSeconds,
            ResultReader<T> reader) throws SQLException {
        if (!sql.startsWith("SELECT ") || rowLimit < 1 || rowLimit >= Integer.MAX_VALUE || timeoutSeconds < 1) {
            throw new IllegalArgumentException("invalid read-only probe");
        }
        io.events.add(Map.of("stage", "READ_ONLY_OBSERVATION", "sql", sql, "rowLimit", rowLimit));
        T result;
        try (Statement statement = bindings.isEmpty() ? io.connection().createStatement()
                : io.connection().prepareStatement(sql)) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(rowLimit + 1);
            statement.setFetchSize(Integer.MIN_VALUE);
            if (statement instanceof PreparedStatement) {
                bind((PreparedStatement) statement, bindings);
            }
            try (ResultSet rows = statement instanceof PreparedStatement
                    ? ((PreparedStatement) statement).executeQuery() : statement.executeQuery(sql)) {
                result = reader.read(rows);
            }
            SQLWarning warning = statement.getWarnings();
            if (warning != null) {
                throw new SQLException("probe warning: " + warning.getMessage(), warning.getSQLState(),
                        warning.getErrorCode());
            }
        }
        return result;
    }

    static void bind(PreparedStatement statement, JsonArray values) throws SQLException {
        for (int i = 0; i < values.size(); i++) {
            JsonObject cell = values.get(i).getAsJsonObject();
            switch (cell.get("type").getAsString()) {
            case "integer":
            case "decimal":
                statement.setBigDecimal(i + 1, new BigDecimal(cell.get("value").getAsString()));
                break;
            case "binary":
                statement.setBytes(i + 1, Base64.getDecoder().decode(cell.get("value").getAsString()));
                break;
            case "boolean":
                statement.setBoolean(i + 1, cell.get("value").getAsBoolean());
                break;
            case "null":
                statement.setNull(i + 1, Types.NULL);
                break;
            default:
                statement.setString(i + 1, cell.get("value").getAsString());
                break;
            }
        }
    }
}
