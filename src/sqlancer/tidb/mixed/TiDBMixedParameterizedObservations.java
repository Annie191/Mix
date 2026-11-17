package sqlancer.tidb.mixed;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import sqlancer.tidb.TiDBSchema.TiDBColumn;
import sqlancer.tidb.ast.TiDBBinaryLogicalOperation;
import sqlancer.tidb.ast.TiDBColumnReference;
import sqlancer.tidb.ast.TiDBExpression;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.ast.TiDBUnaryPrefixOperation;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;
import sqlancer.tidb.mixed.TiDBMixedObservations.Probe;
import sqlancer.tidb.visitor.TiDBVisitor;

/** Compiles bounded SELECT templates from immutable AST targets; values are JDBC bindings, never SQL text. */
final class TiDBMixedParameterizedObservations {
    private final Map<String, JsonObject> catalog = new LinkedHashMap<>();
    private final Map<String, Function<JsonObject, Probe>> compilers = new LinkedHashMap<>();

    TiDBMixedParameterizedObservations(Candidate candidate, String before) {
        String where = candidate.ast.getWhereClause() == null ? null
                : TiDBVisitor.asString(candidate.ast.getWhereClause());
        for (sqlancer.tidb.TiDBSchema.TiDBTable table : candidate.tables) {
            for (TiDBColumn column : table.getColumns()) {
                String id = "key_rows_" + table.getName() + "." + column.getName();
                JsonObject parameters = new JsonObject();
                parameters.add("key",
                        TiDBMixedObservationRequest.valueSchema(TiDBMixedMetadata.valueKind(column.getSqlType())));
                parameters.add("stage", TiDBMixedObservationRequest.enumSchema("base", "where_input", "where_output"));
                add(id, "key_scoped_rows",
                        "Exact rows with this typed key; base existence and actual whole-query JOIN output are distinct; outer rows included",
                        parameters, "count", values -> {
                            JsonArray bindings = new JsonArray();
                            String stage = values.get("stage").getAsString();
                            String relation = stage.equals("base") ? "FROM " + quote(table.getName()) : before;
                            String condition = equality(ref(column), values.getAsJsonObject("key"), bindings);
                            String sql = "SELECT COUNT(*) AS count "
                                    + filter(relation, condition, stage.equals("where_output") ? where : null);
                            return compiled(id, sql, "count", values, bindings);
                        });
            }
        }
        predicates(candidate.ast.getWhereClause(), "predicate_where", before, where, true);
        int next = 0;
        for (TiDBExpression join : candidate.ast.getJoinList()) {
            next = joins(join, next, before, where);
        }
        List<TiDBExpression> keys = candidate.ast.getGroupByExpressions();
        if (!keys.isEmpty() && keys.stream().allMatch(TiDBColumnReference.class::isInstance)) {
            JsonObject parameters = new JsonObject();
            for (int i = 0; i < keys.size(); i++) {
                TiDBColumn column = ((TiDBColumnReference) keys.get(i)).getColumn();
                parameters.add("key" + i,
                        TiDBMixedObservationRequest.valueSchema(TiDBMixedMetadata.valueKind(column.getSqlType())));
            }
            add("group_input_rows", "specific_group_input_rows",
                    "Exact input rows for the complete explicit column GROUP BY key tuple, after original WHERE and before HAVING; not aggregate result cardinality",
                    parameters, "count", values -> {
                        JsonArray bindings = new JsonArray();
                        List<String> conditions = new java.util.ArrayList<>();
                        for (int i = 0; i < keys.size(); i++) {
                            conditions.add(equality(TiDBVisitor.asString(keys.get(i)),
                                    values.getAsJsonObject("key" + i), bindings));
                        }
                        return compiled("group_input_rows",
                                "SELECT COUNT(*) AS count " + filter(before, String.join(" AND ", conditions), where),
                                "count", values, bindings);
                    });
        }
    }

    private static String quote(String value) {
        return TiDBMixedSupport.quoteIdentifier(value);
    }

    private static String ref(TiDBColumn column) {
        return quote(column.getTable().getName()) + "." + quote(column.getName());
    }

    private static String filter(String relation, String condition, String where) {
        return relation + " WHERE (" + condition + ")" + (where == null ? "" : " AND (" + where + ")");
    }

    private static String equality(String expression, JsonObject value, JsonArray bindings) {
        if (value.get("type").getAsString().equals("null")) {
            return expression + " IS NULL";
        }
        bindings.add(value.deepCopy());
        return expression + " = ?";
    }

    private void add(String id, String metric, String description, JsonObject parameters, String kind,
            Function<JsonObject, Probe> compiler) {
        JsonObject capability = new JsonObject();
        capability.addProperty("id", id);
        capability.addProperty("metric", metric);
        capability.add("target", new JsonObject());
        capability.addProperty("description", description);
        capability.add("parameters", parameters);
        capability.add("goal_fields", TiDBMixedPreparationGoals.fields(kind));
        catalog.put(id, capability);
        compilers.put(id, compiler);
    }

    private Probe compiled(String id, String sql, String kind, JsonObject parameters, JsonArray bindings) {
        JsonObject capability = catalog.get(id);
        return new Probe(id, capability.get("metric").getAsString(), capability.getAsJsonObject("target"),
                capability.get("description").getAsString(), sql, kind, null, parameters, bindings);
    }

    private void predicates(TiDBExpression expression, String id, String relation, String where, boolean allowOutput) {
        if (expression == null) {
            return;
        }
        String predicate = TiDBVisitor.asString(expression);
        JsonObject parameters = new JsonObject();
        parameters.add("stage", allowOutput ? TiDBMixedObservationRequest.enumSchema("input", "output")
                : TiDBMixedObservationRequest.enumSchema("input"));
        add(id, "predicate_truth_distribution",
                "TRUE/FALSE/NULL distribution of AST predicate " + predicate + "; input scope: " + relation
                        + (allowOutput ? "; output scope additionally applies complete original WHERE when present"
                                : "; local ON input only, before applying this ON; no output stage"),
                parameters, "truth", values -> {
                    List<String> columns = new java.util.ArrayList<>();
                    for (String truth : List.of("TRUE", "FALSE", "NULL")) {
                        String name = truth.toLowerCase(java.util.Locale.ROOT) + "_count";
                        columns.add("COALESCE(SUM(CASE WHEN (" + predicate + ") IS " + truth
                                + " THEN 1 ELSE 0 END),0) AS " + name);
                    }
                    String source = relation;
                    if (values.get("stage").getAsString().equals("output") && where != null) {
                        source += " WHERE (" + where + ")";
                    }
                    return compiled(id, "SELECT " + String.join(",", columns) + " " + source, "truth", values,
                            new JsonArray());
                });
        if (expression instanceof TiDBBinaryLogicalOperation) {
            TiDBBinaryLogicalOperation logical = (TiDBBinaryLogicalOperation) expression;
            predicates(logical.getLeft(), id + "_l", relation, where, allowOutput);
            predicates(logical.getRight(), id + "_r", relation, where, allowOutput);
        } else if (expression instanceof TiDBUnaryPrefixOperation) {
            predicates(((TiDBUnaryPrefixOperation) expression).getExpression(), id + "_u", relation, where,
                    allowOutput);
        }
    }

    private int joins(TiDBExpression expression, int next, String before, String where) {
        if (!(expression instanceof TiDBJoin)) {
            return next;
        }
        TiDBJoin join = (TiDBJoin) expression;
        String id = "predicate_j" + next + "_on";
        String input = "FROM (" + TiDBVisitor.asString(join.getLeftTable()) + ") CROSS JOIN ("
                + TiDBVisitor.asString(join.getRightTable()) + ")";
        // Local ON inputs precede this JOIN; a separate capability measures rows surviving the full FROM/JOIN.
        predicates(join.getOnCondition(), id, input, null, false);
        predicates(join.getOnCondition(), id + "_query", before, where, true);
        if (join.getOnCondition() != null) {
            for (TiDBColumn column : TiDBMixedQueryStages.columns(join.getOnCondition())) {
                String keyId = "key_matches_j" + next + "_" + column.getTable().getName() + "." + column.getName();
                JsonObject parameters = new JsonObject();
                parameters.add("key",
                        TiDBMixedObservationRequest.valueSchema(TiDBMixedMetadata.valueKind(column.getSqlType())));
                parameters.add("stage",
                        TiDBMixedObservationRequest.enumSchema("local_pairs", "query_input", "where_output"));
                add(keyId, "key_scoped_join_matches",
                        "Typed key and original ON=TRUE; local_pairs counts this JOIN's child-input pairs; query_input/where_output count whole-query rows (downstream multiplicity retained)",
                        parameters, "count", values -> {
                            JsonArray bindings = new JsonArray();
                            String stage = values.get("stage").getAsString();
                            String condition = equality(ref(column), values.getAsJsonObject("key"), bindings)
                                    + " AND ((" + TiDBVisitor.asString(join.getOnCondition()) + ") IS TRUE)";
                            String relation = stage.equals("local_pairs") ? input : before;
                            return compiled(keyId,
                                    "SELECT COUNT(*) AS count "
                                            + filter(relation, condition, stage.equals("where_output") ? where : null),
                                    "count", values, bindings);
                        });
            }
        }
        int afterLeft = joins(join.getLeftTable(), next + 1, before, where);
        return joins(join.getRightTable(), afterLeft, before, where);
    }

    JsonArray available() {
        JsonArray result = new JsonArray();
        catalog.values().forEach(capability -> result.add(capability.deepCopy()));
        return result;
    }

    Probe compile(JsonElement request) {
        String id = TiDBMixedObservationRequest.capabilityId(request);
        JsonObject parameters = TiDBMixedObservationRequest.parameters(catalog.get(id),
                request.getAsJsonObject().get("parameters"));
        return compilers.get(id).apply(parameters);
    }
}
