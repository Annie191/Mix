package sqlancer.tidb.mixed;

import java.math.BigInteger;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import sqlancer.common.ast.BinaryNode;
import sqlancer.common.ast.FunctionNode;
import sqlancer.common.ast.UnaryNode;
import sqlancer.tidb.TiDBSchema.TiDBColumn;
import sqlancer.tidb.TiDBSchema.TiDBDataType;
import sqlancer.tidb.ast.TiDBAggregate;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation;
import sqlancer.tidb.ast.TiDBBinaryComparisonOperation.TiDBComparisonOperator;
import sqlancer.tidb.ast.TiDBBinaryLogicalOperation;
import sqlancer.tidb.ast.TiDBBinaryLogicalOperation.TiDBBinaryLogicalOperator;
import sqlancer.tidb.ast.TiDBCase;
import sqlancer.tidb.ast.TiDBCastOperation;
import sqlancer.tidb.ast.TiDBColumnReference;
import sqlancer.tidb.ast.TiDBConstant.TiDBIntConstant;
import sqlancer.tidb.ast.TiDBExpression;
import sqlancer.tidb.ast.TiDBFunctionCall;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.ast.TiDBSelect;
import sqlancer.tidb.visitor.TiDBVisitor;

/** Logical relational-stage witnesses, not a claim about physical evaluation or optimizer ordering. */
final class TiDBMixedQueryStages {
    private TiDBMixedQueryStages() {
    }

    @FunctionalInterface
    interface Counter {
        void count(String id, String description, String relation);
    }

    private static void walk(TiDBExpression expression, Consumer<TiDBExpression> visitor) {
        if (expression == null) {
            return;
        }
        visitor.accept(expression);
        if (expression instanceof BinaryNode<?>) {
            BinaryNode<?> node = (BinaryNode<?>) expression;
            walk((TiDBExpression) node.getLeft(), visitor);
            walk((TiDBExpression) node.getRight(), visitor);
        } else if (expression instanceof UnaryNode<?>) {
            walk((TiDBExpression) ((UnaryNode<?>) expression).getExpression(), visitor);
        } else if (expression instanceof FunctionNode<?, ?>) {
            ((FunctionNode<?, ?>) expression).getArgs().forEach(arg -> walk((TiDBExpression) arg, visitor));
        } else if (expression instanceof TiDBFunctionCall) {
            ((TiDBFunctionCall) expression).getArgs().forEach(arg -> walk(arg, visitor));
        } else if (expression instanceof TiDBCastOperation) {
            walk(((TiDBCastOperation) expression).getExpr(), visitor);
        } else if (expression instanceof TiDBCase) {
            TiDBCase node = (TiDBCase) expression;
            walk(node.getSwitchCondition(), visitor);
            node.getConditions().forEach(child -> walk(child, visitor));
            node.getExpressions().forEach(child -> walk(child, visitor));
            walk(node.getElseExpr(), visitor);
        }
    }

    static Set<TiDBColumn> columns(TiDBExpression expression) {
        Set<TiDBColumn> columns = new LinkedHashSet<>();
        walk(expression, node -> {
            if (node instanceof TiDBColumnReference) {
                columns.add(((TiDBColumnReference) node).getColumn());
            }
        });
        return columns;
    }

    private static void joinColumns(TiDBExpression expression, Set<TiDBColumn> columns) {
        if (expression instanceof TiDBJoin) {
            TiDBJoin join = (TiDBJoin) expression;
            columns.addAll(columns(join.getOnCondition()));
            joinColumns(join.getLeftTable(), columns);
            joinColumns(join.getRightTable(), columns);
        }
    }

    private static String filtered(String before, TiDBExpression where, String condition) {
        return before + " WHERE (" + condition + ")"
                + (where == null ? "" : " AND (" + TiDBVisitor.asStringDeterministic(where) + ")");
    }

    private static void nulls(Counter counter, String stage, Set<TiDBColumn> columns, String before,
            TiDBExpression where) {
        for (TiDBColumn column : columns) {
            String name = column.getTable().getName() + "." + column.getName();
            String expression = TiDBMixedSupport.quoteIdentifier(column.getTable().getName()) + "."
                    + TiDBMixedSupport.quoteIdentifier(column.getName());
            counter.count(stage + "_nulls_" + name,
                    "NULL column " + name + " at logical " + stage
                            + "; includes outer NULL extension; does not prove arbitrary expression evaluates to NULL",
                    filtered(before, where, expression + " IS NULL"));
        }
    }

    static void plan(TiDBSelect select, String before, Counter counter) {
        Set<TiDBColumn> referenced = new LinkedHashSet<>();
        Set<TiDBColumn> aggregateColumns = new LinkedHashSet<>();
        Set<String> aggregateArgs = new LinkedHashSet<>();
        Consumer<TiDBExpression> collect = expression -> {
            referenced.addAll(columns(expression));
            walk(expression, node -> {
                if (node instanceof TiDBAggregate) {
                    ((TiDBAggregate) node).getArgs().forEach(arg -> aggregateColumns.addAll(columns(arg)));
                    ((TiDBAggregate) node).getArgs()
                            .forEach(arg -> aggregateArgs.add(TiDBVisitor.asStringDeterministic(arg)));
                }
            });
        };
        collect.accept(select.getWhereClause());
        collect.accept(select.getHavingClause());
        select.getFetchColumns().forEach(collect);
        select.getGroupByExpressions().forEach(collect);
        select.getJoinList().forEach(join -> joinColumns(join, referenced));
        nulls(counter, "where_input", referenced, before, null);
        nulls(counter, "where_output", referenced, before, select.getWhereClause());
        nulls(counter, "aggregate_input", aggregateColumns, before, select.getWhereClause());
        int argument = 0;
        for (String expression : aggregateArgs) {
            counter.count("aggregate_arg" + argument++ + "_nulls", "NULL value of aggregate argument " + expression
                    + " after complete WHERE, before aggregation (not aggregate output or NULL source-column count)",
                    filtered(before, select.getWhereClause(), "(" + expression + ") IS NULL"));
        }
        Set<TiDBColumn> groupColumns = new LinkedHashSet<>();
        select.getGroupByExpressions().forEach(key -> groupColumns.addAll(columns(key)));
        nulls(counter, "group_input", groupColumns, before, select.getWhereClause());
        if (select.getWhereClause() != null) {
            for (String truth : List.of("TRUE", "FALSE", "NULL")) {
                counter.count(
                        "where_predicate_"
                                + (truth.equals("NULL") ? "unknown" : truth.toLowerCase(java.util.Locale.ROOT)),
                        "Complete original WHERE evaluates to " + truth
                                + " on original FROM/JOIN rows; logical truth value",
                        filtered(before, null,
                                "(" + TiDBVisitor.asStringDeterministic(select.getWhereClause()) + ") IS " + truth));
            }
        }
        boundaries(select.getWhereClause(), before, select.getWhereClause(), "where_boundary", counter);
    }

    private static void boundaries(TiDBExpression expression, String before, TiDBExpression where, String id,
            Counter counter) {
        if (expression == null) {
            return;
        }
        if (expression instanceof TiDBBinaryLogicalOperation
                && ((TiDBBinaryLogicalOperation) expression).getOp() == TiDBBinaryLogicalOperator.AND) {
            TiDBBinaryLogicalOperation and = (TiDBBinaryLogicalOperation) expression;
            boundaries(and.getLeft(), before, where, id + "_l", counter);
            boundaries(and.getRight(), before, where, id + "_r", counter);
            return;
        }
        TiDBColumnReference column = null;
        TiDBIntConstant bound = null;
        if (expression instanceof TiDBBinaryComparisonOperation) {
            TiDBBinaryComparisonOperation comparison = (TiDBBinaryComparisonOperation) expression;
            if (List.of(TiDBComparisonOperator.GREATER, TiDBComparisonOperator.GREATER_EQUALS,
                    TiDBComparisonOperator.SMALLER, TiDBComparisonOperator.SMALLER_EQUALS,
                    TiDBComparisonOperator.EQUALS, TiDBComparisonOperator.NOT_EQUALS).contains(comparison.getOp())) {
                if (comparison.getLeft() instanceof TiDBColumnReference
                        && comparison.getRight() instanceof TiDBIntConstant) {
                    column = (TiDBColumnReference) comparison.getLeft();
                    bound = (TiDBIntConstant) comparison.getRight();
                } else if (comparison.getRight() instanceof TiDBColumnReference
                        && comparison.getLeft() instanceof TiDBIntConstant) {
                    column = (TiDBColumnReference) comparison.getRight();
                    bound = (TiDBIntConstant) comparison.getLeft();
                }
            }
        }
        if (column == null || bound == null
                || column.getColumn().getType().getPrimitiveDataType() != TiDBDataType.INT) {
            counter.count(id + "_unsupported", "Unsupported boundary: " + TiDBVisitor.asStringDeterministic(expression)
                    + "; requires integer column vs integer constant, optionally within AND; OR/NOT/casts are not inferred",
                    null);
            return;
        }
        for (int delta : List.of(-1, 0, 1)) {
            String point = delta < 0 ? "below" : delta == 0 ? "at" : "above";
            BigInteger value = BigInteger.valueOf(bound.getValue()).add(BigInteger.valueOf(delta));
            String condition = TiDBVisitor.asStringDeterministic(column) + "=" + value;
            String description = "Boundary of predicate " + TiDBVisitor.asStringDeterministic(expression) + "; "
                    + TiDBVisitor.asStringDeterministic(column) + "=" + value;
            counter.count("base_" + id + "_" + point, description + "; base-table existence only",
                    "FROM " + TiDBMixedSupport.quoteIdentifier(column.getColumn().getTable().getName()) + " WHERE "
                            + condition);
            counter.count(id + "_" + point + "_before", description
                    + "; rows in original FROM/JOIN reaching WHERE, before any WHERE conjunct (not table existence)",
                    filtered(before, null, condition));
            counter.count(id + "_" + point + "_after", description + "; rows surviving the complete original WHERE",
                    filtered(before, where, condition));
        }
    }
}
