package sqlancer.tidb.gen;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.tidb.TiDBErrors;
import sqlancer.tidb.TiDBExpressionGenerator;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.TiDBSchema.TiDBColumn;
import sqlancer.tidb.TiDBSchema.TiDBTable;
import sqlancer.tidb.ast.TiDBConstant;
import sqlancer.tidb.ast.TiDBExpression;
import sqlancer.tidb.visitor.TiDBVisitor;

public class TiDBInsertGenerator {

    private final TiDBGlobalState globalState;
    private final ExpectedErrors errors = new ExpectedErrors();
    private TiDBExpressionGenerator gen;
    private BiFunction<TiDBColumn, TiDBExpression, TiDBExpression> valuePolicy = (c, v) -> v;

    public static final class AppendRow {
        public final String sql;
        public final List<TiDBColumn> columns;
        public final List<Object> values;

        private AppendRow(String sql, List<TiDBColumn> columns, List<Object> values) {
            this.sql = sql;
            this.columns = List.copyOf(columns);
            this.values = java.util.Collections.unmodifiableList(new ArrayList<>(values));
        }
    }

    // A separate, one-row ordinary INSERT path: no REPLACE, IGNORE, UPDATE, modifiers, or SQL-valued inputs.
    public static AppendRow appendRow(TiDBGlobalState state, TiDBTable table, Map<String, Object> specified) {
        if (table.isView() || specified.isEmpty()) {
            throw new IllegalArgumentException("append needs a base table and specified values");
        }
        for (String name : specified.keySet()) {
            if (table.getColumns().stream().noneMatch(c -> c.getName().equals(name) && !c.isGenerated())) {
                throw new IllegalArgumentException("unknown or generated append column: " + name);
            }
        }
        TiDBExpressionGenerator generator = new TiDBExpressionGenerator(state);
        List<TiDBColumn> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (TiDBColumn column : table.getColumns()) {
            if (column.isGenerated() || column.isAutoIncrement() && !specified.containsKey(column.getName())) {
                continue;
            }
            columns.add(column);
            values.add(specified.containsKey(column.getName()) ? specified.get(column.getName())
                    : constantValue(generator.generateConstant(column.getType().getPrimitiveDataType())));
        }
        String names = columns.stream().map(c -> quote(c.getName())).collect(Collectors.joining(","));
        return new AppendRow("INSERT INTO " + quote(table.getName()) + " (" + names + ") VALUES ("
                + columns.stream().map(c -> "?").collect(Collectors.joining(",")) + ")", columns, values);
    }

    private static String quote(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static Object constantValue(TiDBExpression expression) {
        if (expression instanceof TiDBConstant.TiDBNullConstant) {
            return null;
        }
        if (expression instanceof TiDBConstant.TiDBIntConstant) {
            return ((TiDBConstant.TiDBIntConstant) expression).getValue();
        }
        if (expression instanceof TiDBConstant.TiDBDoubleConstant) {
            return ((TiDBConstant.TiDBDoubleConstant) expression).getValue();
        }
        if (expression instanceof TiDBConstant.TiDBBooleanConstant) {
            return ((TiDBConstant.TiDBBooleanConstant) expression).getValue();
        }
        if (expression instanceof TiDBConstant.TiDBTextConstant) {
            return ((TiDBConstant.TiDBTextConstant) expression).getValue();
        }
        throw new IllegalArgumentException("append generator requires a constant value");
    }

    public static SQLQueryAdapter getQuery(TiDBGlobalState state, TiDBTable table,
            BiFunction<TiDBColumn, TiDBExpression, TiDBExpression> policy) {
        TiDBInsertGenerator generator = new TiDBInsertGenerator(state);
        generator.valuePolicy = policy;
        return generator.get(table);
    }

    public TiDBInsertGenerator(TiDBGlobalState globalState) {
        this.globalState = globalState;
        TiDBErrors.addInsertErrors(errors);
    }

    public static SQLQueryAdapter getQuery(TiDBGlobalState globalState) throws SQLException {
        TiDBTable table = globalState.getSchema().getRandomTable(t -> !t.isView());
        return new TiDBInsertGenerator(globalState).get(table);
    }

    public static SQLQueryAdapter getQuery(TiDBGlobalState globalState, TiDBTable table) {
        return new TiDBInsertGenerator(globalState).get(table);
    }

    private SQLQueryAdapter get(TiDBTable table) {
        gen = new TiDBExpressionGenerator(globalState).setColumns(table.getColumns());
        StringBuilder sb = new StringBuilder();
        boolean isInsert = Randomly.getBoolean();
        if (isInsert) {
            sb.append("INSERT");
        } else {
            sb.append("REPLACE");
        }
        if (Randomly.getBooleanWithRatherLowProbability()) {
            sb.append(" ");
            sb.append(Randomly.fromOptions("LOW_PRIORITY", "HIGH_PRIORITY", "DELAYED"));
        }
        if (isInsert && Randomly.getBoolean()) {
            sb.append(" IGNORE ");
        }
        sb.append(" INTO ");
        sb.append(table.getName());
        if (Randomly.getBoolean()) {
            sb.append(" VALUES ");
            List<TiDBColumn> columns = table.getColumns();
            insertColumns(sb, columns);
        } else {
            List<TiDBColumn> columnSubset = table.getRandomNonEmptyColumnSubset();
            sb.append("(");
            sb.append(columnSubset.stream().map(c -> c.getName()).collect(Collectors.joining(", ")));
            sb.append(") VALUES ");
            insertColumns(sb, columnSubset);
        }
        if (isInsert && Randomly.getBoolean()) {
            sb.append(" ON DUPLICATE KEY UPDATE ");
            sb.append(table.getRandomColumn().getName());
            sb.append("=");
            sb.append(TiDBVisitor.asString(gen.generateExpression()));
        }
        errors.add("Illegal mix of collations");
        return new SQLQueryAdapter(sb.toString(), errors);
    }

    private void insertColumns(StringBuilder sb, List<TiDBColumn> columns) {
        for (int nrRows = 0; nrRows < Randomly.smallNumber() + 1; nrRows++) {
            if (nrRows != 0) {
                sb.append(", ");
            }
            sb.append("(");
            int i = 0;
            for (TiDBColumn c : columns) {
                if (i++ != 0) {
                    sb.append(", ");
                }
                TiDBExpression randomValue = gen.generateConstant(c.getType().getPrimitiveDataType());
                sb.append(TiDBVisitor.asString(valuePolicy.apply(c, randomValue)));
            }
            sb.append(")");
        }
    }
}
