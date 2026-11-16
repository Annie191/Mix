package sqlancer.tidb.mixed;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import sqlancer.tidb.ast.TiDBExpression;
import sqlancer.tidb.ast.TiDBJoin;
import sqlancer.tidb.ast.TiDBSelect;
import sqlancer.tidb.ast.TiDBTableReference;
import sqlancer.tidb.mixed.TiDBMixedSupport.Config;
import sqlancer.tidb.mixed.TiDBMixedSupport.Run;

/** Observes primary/alternative executions without influencing configuration sampling or verdicts. */
public final class TiDBMixedCoverage {
    private final Map<String, Counter> ratios = new LinkedHashMap<>();
    private final Map<String, Counter> joins = new LinkedHashMap<>();
    private final Counter paths = new Counter();
    private final Counter pathsWithoutExplicitJoin = new Counter();

    private static final class Counter {
        private long attempted;
        private long valid;
        private final Map<String, Long> statuses = new LinkedHashMap<>();

        void record(String status) {
            attempted++;
            if (status.equals("OK")) {
                valid++;
            }
            statuses.merge(status, 1L, Long::sum);
        }

        Map<String, Object> snapshot() {
            return Map.of("attempted", attempted, "valid", valid, "failed", attempted - valid, "statuses",
                    new LinkedHashMap<>(statuses));
        }
    }

    public Map<String, Object> record(TiDBSelect query, List<Run> runs) {
        TiDBMixedCoverage batch = new TiDBMixedCoverage();
        for (Run run : runs) {
            recordRun(query, run);
            batch.recordRun(query, run);
        }
        return batch.snapshot();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> ratioSnapshot = new LinkedHashMap<>();
        ratios.forEach((key, count) -> ratioSnapshot.put(key, count.snapshot()));
        Map<String, Object> joinSnapshot = new LinkedHashMap<>();
        joins.forEach((key, count) -> joinSnapshot.put(key, count.snapshot()));
        return Map.of("paths", paths.snapshot(), "ratios", ratioSnapshot, "joins", joinSnapshot,
                "pathsWithoutExplicitJoin", pathsWithoutExplicitJoin.snapshot());
    }

    private static String ratioKey(int tableCount, long flashCount) {
        return "tables=" + tableCount + ";tikv=" + (tableCount - flashCount) + ";tiflash=" + flashCount;
    }

    private static String joinKey(int tableCount, String type, String left, String right) {
        return "tables=" + tableCount + ";type=" + type + ";left=" + left + ";right=" + right;
    }

    private static void collectJoins(TiDBExpression expression, List<TiDBJoin> result) {
        if (expression instanceof TiDBJoin) {
            TiDBJoin join = (TiDBJoin) expression;
            result.add(join);
            collectJoins(join.getLeftTable(), result);
            collectJoins(join.getRightTable(), result);
        }
    }

    private static Set<String> tables(TiDBExpression expression) {
        if (expression instanceof TiDBTableReference) {
            return Set.of(((TiDBTableReference) expression).getTable().getName());
        }
        if (expression instanceof TiDBJoin) {
            TiDBJoin join = (TiDBJoin) expression;
            Set<String> result = new LinkedHashSet<>(tables(join.getLeftTable()));
            result.addAll(tables(join.getRightTable()));
            return result;
        }
        throw new IllegalArgumentException("coverage requires generated table references or joins");
    }

    private static String engine(Set<String> tables, Config config) {
        if (!config.engines.keySet().containsAll(tables)) {
            throw new IllegalArgumentException("configuration does not cover JOIN tables");
        }
        Set<String> engines = tables.stream().map(config.engines::get).collect(Collectors.toSet());
        return engines.size() == 1 ? engines.iterator().next() : "mixed";
    }

    private static List<String> possibleEngines(Set<String> tables) {
        return tables.size() == 1 ? List.of("tikv", "tiflash") : List.of("tikv", "tiflash", "mixed");
    }

    private void recordRun(TiDBSelect query, Run run) {
        int tableCount = run.config.engines.size();
        for (int flash = 0; flash <= tableCount; flash++) {
            ratios.computeIfAbsent(ratioKey(tableCount, flash), key -> new Counter());
        }
        long flashCount = run.config.engines.values().stream().filter("tiflash"::equals).count();
        ratios.get(ratioKey(tableCount, flashCount)).record(run.status);
        paths.record(run.status);
        List<TiDBJoin> queryJoins = new ArrayList<>();
        query.getJoinList().forEach(expression -> collectJoins(expression, queryJoins));
        query.getFromList().forEach(expression -> collectJoins(expression, queryJoins));
        if (queryJoins.isEmpty()) {
            pathsWithoutExplicitJoin.record(run.status);
        }
        for (TiDBJoin join : queryJoins) {
            String type = join.getJoinType().name();
            if (join.getJoinType() == TiDBJoin.JoinType.NATURAL) {
                type += "_" + join.getNaturalJoinType().name();
            }
            Set<String> left = tables(join.getLeftTable());
            Set<String> right = tables(join.getRightTable());
            for (String leftEngine : possibleEngines(left)) {
                for (String rightEngine : possibleEngines(right)) {
                    joins.computeIfAbsent(joinKey(tableCount, type, leftEngine, rightEngine), key -> new Counter());
                }
            }
            joins.get(joinKey(tableCount, type, engine(left, run.config), engine(right, run.config)))
                    .record(run.status);
        }
    }
}
