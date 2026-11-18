package sqlancer.tidb.mixed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import sqlancer.IgnoreMeException;
import sqlancer.Randomly;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.tidb.TiDBExpressionGenerator;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.TiDBSchema.TiDBColumn;
import sqlancer.tidb.TiDBSchema.TiDBDataType;
import sqlancer.tidb.TiDBSchema.TiDBTable;
import sqlancer.tidb.ast.TiDBAggregate;
import sqlancer.tidb.ast.TiDBAggregate.TiDBAggregateFunction;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation.TiDBComparisonOperator;
import sqlancer.tidb.ast.TiDBColumnReference;
import sqlancer.tidb.ast.TiDBConstant;
import sqlancer.tidb.ast.TiDBExpression;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.ast.TiDBSelect;
import sqlancer.tidb.ast.TiDBTableReference;
import sqlancer.tidb.gen.TiDBIndexGenerator;
import sqlancer.tidb.gen.TiDBInsertGenerator;
import sqlancer.tidb.gen.TiDBTableGenerator;
import sqlancer.tidb.visitor.TiDBVisitor;

public final class TiDBMixedGeneration {
    private TiDBMixedGeneration() {
    }

    public static final class Candidate {
        public final TiDBSelect ast;
        public final List<TiDBTable> tables;
        public final List<String> guidedColumns;
        public final String sql;

        Candidate(TiDBSelect ast, List<TiDBTable> tables, List<String> guidedColumns) {
            this.ast = ast;
            this.tables = tables;
            this.guidedColumns = guidedColumns;
            // Render candidates and all derived probes consistently, then freeze the query text.
            this.sql = TiDBVisitor.asStringDeterministic(ast);
        }
    }

    public static void createEmptySchema(TiDBGlobalState state) throws Exception {
        int max = Math.min(state.getDbmsSpecificOptions().maxNumTables, 5);
        if (max < 2) {
            throw new IllegalArgumentException("mixed table assignment needs max-num-tables >= 2");
        }
        int target = state.getRandomly().getInteger(2, max + 1);
        for (int attempts = 0; baseTables(state).size() < target && attempts < 100; attempts++) {
            try {
                state.executeStatement(new TiDBTableGenerator().getQuery(state));
            } catch (IgnoreMeException ignored) {
                // Bounded retry; other generator/DB failures are not swallowed.
            }
        }
        state.updateSchema();
        if (baseTables(state).size() < 2) {
            throw new IgnoreMeException();
        }
        for (int i = 0; i < Randomly.smallNumber(); i++) {
            try {
                state.executeStatement(TiDBIndexGenerator.getQuery(state));
            } catch (IgnoreMeException ignored) {
                // Keep the schema even if an optional index cannot be generated.
            }
        }
        state.updateSchema(); // Deliberately no INSERT, UPDATE, DELETE or random SET here.
    }

    public static List<TiDBTable> baseTables(TiDBGlobalState state) {
        return state.getSchema().getDatabaseTables().stream().filter(t -> !t.isView()).collect(Collectors.toList());
    }

    static TiDBColumn integerColumn(TiDBTable table) {
        List<TiDBColumn> columns = table.getColumns().stream()
                .filter(c -> c.getType().getPrimitiveDataType() == TiDBDataType.INT && !c.isGenerated())
                .collect(Collectors.toList());
        return columns.isEmpty() ? null : Randomly.fromList(columns);
    }

    private static void ensureIntegerColumn(TiDBGlobalState state, TiDBTable table, TiDBMixedSupport io)
            throws Exception {
        if (integerColumn(table) != null) {
            return;
        }
        String name = "ms_join_" + table.getColumns().size();
        while (table.getColumns().stream().map(TiDBColumn::getName).collect(Collectors.toSet()).contains(name)) {
            name += "x";
        }
        String type = Randomly.fromOptions("SMALLINT", "INT", "BIGINT");
        io.exec("ALTER TABLE " + TiDBMixedSupport.quoteIdentifier(table.getName()) + " ADD COLUMN "
                + TiDBMixedSupport.quoteIdentifier(name) + " " + type + " NULL");
        state.updateSchema(); // Do not use pre-DDL TiDBColumn objects after this point.
    }

    public static Candidate query(TiDBGlobalState state, boolean guided, TiDBMixedSupport io) throws Exception {
        List<TiDBTable> selected = Randomly.nonEmptySubset(baseTables(state));
        if (selected.size() < 2) {
            for (TiDBTable table : baseTables(state)) {
                if (!selected.contains(table)) {
                    selected = new ArrayList<>(selected);
                    selected.add(table);
                    break;
                }
            }
        }
        List<String> names = selected.stream().map(TiDBTable::getName).collect(Collectors.toList());
        if (guided) {
            ensureIntegerColumn(state, selected.get(0), io);
            ensureIntegerColumn(state, selected.get(1), io);
            selected = names.stream()
                    .map(n -> baseTables(state).stream().filter(t -> t.getName().equals(n)).findFirst().orElseThrow())
                    .collect(Collectors.toList());
        }
        List<TiDBColumn> columns = selected.stream().flatMap(t -> t.getColumns().stream()).collect(Collectors.toList());
        TiDBExpressionGenerator gen = new TiDBExpressionGenerator(state).setColumns(columns);
        List<TiDBExpression> refs = selected.stream().map(TiDBTableReference::new).collect(Collectors.toList());
        List<TiDBExpression> joins = new ArrayList<>();
        List<String> guidedColumns = new ArrayList<>();
        if (guided) {
            TiDBColumn left = integerColumn(selected.get(0));
            TiDBColumn right = integerColumn(selected.get(1));
            guidedColumns.add(left.getFullQualifiedName());
            guidedColumns.add(right.getFullQualifiedName());
            TiDBExpression condition = new TiDBBinaryComparisonOperation(new TiDBColumnReference(left),
                    new TiDBColumnReference(right), TiDBComparisonOperator.EQUALS);
            TiDBExpression a = refs.remove(0);
            TiDBExpression b = refs.remove(0);
            joins.add(new TiDBJoin(a, b, Randomly.fromOptions(TiDBJoin.JoinType.INNER, TiDBJoin.JoinType.LEFT,
                    TiDBJoin.JoinType.RIGHT, TiDBJoin.JoinType.STRAIGHT), condition));
        }
        joins.addAll(TiDBJoin.getJoins(refs, state));
        TiDBSelect select = new TiDBSelect();
        select.setFromList(refs);
        select.setJoinList(joins);
        List<TiDBExpression> projection = new ArrayList<>();
        for (int i = 0; i <= Randomly.smallNumber(); i++) {
            projection.add(gen.generateExpression());
        }
        if (Randomly.getBoolean()) {
            select.setWhereClause(gen.generateExpression());
        }
        if (Randomly.getBoolean()) {
            List<TiDBExpression> group = Randomly.nonEmptySubset(columns).stream().map(TiDBColumnReference::new)
                    .collect(Collectors.toList());
            select.setGroupByExpressions(group);
            projection = new ArrayList<>(group);
            for (int i = 0; i <= Randomly.smallNumber(); i++) {
                projection.add(new TiDBAggregate(List.of(gen.generateExpression()), TiDBAggregateFunction.getRandom()));
            }
            if (Randomly.getBoolean()) {
                select.setHavingClause(new TiDBBinaryComparisonOperation(
                        new TiDBAggregate(List.of(TiDBConstant.createIntConstant(1)), TiDBAggregateFunction.COUNT),
                        TiDBConstant.createIntConstant(state.getRandomly().getInteger(0, 8)),
                        TiDBComparisonOperator.getRandom()));
            }
        }
        select.setFetchColumns(projection);
        // No LIMIT/ORDER BY in this first integration; no arbitrary SQL templating.
        return new Candidate(select, selected, guidedColumns);
    }

    public static BiFunction<TiDBColumn, TiDBExpression, TiDBExpression> valuePolicy(Candidate query,
            TiDBDeepSeekAdvisor.Profile profile, List<TiDBExpression> sharedPool) {
        return (column, original) -> {
            if (profile == TiDBDeepSeekAdvisor.Profile.NULL_HEAVY && column.isNullable() && Randomly.getBoolean()) {
                return TiDBConstant.createNullConstant();
            }
            if (profile == TiDBDeepSeekAdvisor.Profile.SHARED
                    && query.guidedColumns.contains(column.getFullQualifiedName()) && Randomly.getBoolean()) {
                return Randomly.fromList(sharedPool);
            }
            return original;
        };
    }

    public static Map<String, Object> fill(TiDBGlobalState state, Candidate query, TiDBDeepSeekAdvisor.Profile profile,
            TiDBMixedSupport io) throws Exception {
        List<TiDBExpression> sharedPool = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            sharedPool.add(TiDBConstant.createIntConstant(state.getRandomly().getInteger(0, 100)));
        }
        BiFunction<TiDBColumn, TiDBExpression, TiDBExpression> policy = valuePolicy(query, profile, sharedPool);
        int attempted = 0;
        int successful = 0;
        for (TiDBTable table : baseTables(state)) {
            io.exec("DELETE FROM " + TiDBMixedSupport.quoteIdentifier(table.getName()));
        }
        for (TiDBTable table : query.tables) {
            int inserts = state.getRandomly().getInteger(1, 12);
            for (int i = 0; i < inserts; i++) {
                try {
                    SQLQueryAdapter insert = TiDBInsertGenerator.getQuery(state, table, policy);
                    attempted++;
                    io.events.add(Map.of("stage", "INSERT_ATTEMPT", "sql", insert.getQueryString()));
                    if (state.executeStatement(insert)) {
                        successful++;
                    }
                } catch (IgnoreMeException ignored) {
                    // A bounded candidate rejection does not imply a successful insert.
                }
            }
        }
        io.exec("SET SESSION tidb_isolation_read_engines='tikv'");
        List<Map<String, Object>> coverage = new ArrayList<>();
        for (TiDBTable table : query.tables) {
            coverage.add(Map.of("table", table.getName(), "counts", io.textRows(
                    "SELECT COUNT(*) AS rows_count FROM " + TiDBMixedSupport.quoteIdentifier(table.getName()))));
        }
        for (String qualified : query.guidedColumns) {
            String[] pair = qualified.split("\\.");
            String column = TiDBMixedSupport.quoteIdentifier(pair[1]);
            coverage.add(Map.of("column", qualified, "counts",
                    io.textRows("SELECT COUNT(*) AS rows_count, COUNT(" + column + ") AS nonnull_count, COUNT(DISTINCT "
                            + column + ") AS distinct_count FROM " + TiDBMixedSupport.quoteIdentifier(pair[0]))));
        }
        return Map.of("profile", profile, "insertAttempts", attempted, "successfulStatements", successful, "coverage",
                coverage, "guidedColumns", query.guidedColumns, "sharedPool",
                sharedPool.stream().map(TiDBVisitor::asStringDeterministic).collect(Collectors.toList()));
    }
}
