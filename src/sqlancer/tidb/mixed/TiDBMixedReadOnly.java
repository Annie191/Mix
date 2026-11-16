package sqlancer.tidb.mixed;

import java.math.BigDecimal;
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
            cell.addProperty("value", text);
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
        if (!sql.startsWith("SELECT ") || rowLimit < 1 || rowLimit >= Integer.MAX_VALUE || timeoutSeconds < 1) {
            throw new IllegalArgumentException("invalid read-only probe");
        }
        io.events.add(Map.of("stage", "READ_ONLY_OBSERVATION", "sql", sql, "rowLimit", rowLimit));
        Result result = new Result();
        try (Statement statement = io.connection().createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setMaxRows(rowLimit + 1);
            statement.setFetchSize(Integer.MIN_VALUE);
            try (ResultSet rows = statement.executeQuery(sql)) {
                ResultSetMetaData metadata = rows.getMetaData();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    result.columns.add(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT));
                }
                while (rows.next()) {
                    if (result.rows.size() == rowLimit) {
                        result.complete = false;
                        break;
                    }
                    JsonArray row = new JsonArray();
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        row.add(value(rows, metadata, i));
                    }
                    result.rows.add(row);
                }
            }
            SQLWarning warning = statement.getWarnings();
            if (warning != null) {
                throw new SQLException("probe warning: " + warning.getMessage(), warning.getSQLState(),
                        warning.getErrorCode());
            }
        }
        return result;
    }
}
