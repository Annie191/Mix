package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.JsonParser;

import sqlancer.IgnoreMeException;
import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.tidb.TiDBOptions;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.TiDBSchema.TiDBColumn;
import sqlancer.tidb.TiDBSchema.TiDBCompositeDataType;
import sqlancer.tidb.TiDBSchema.TiDBTable;
import sqlancer.tidb.TiDBSchema;
import sqlancer.tidb.ast.TiDBConstant;
import sqlancer.tidb.gen.TiDBInsertGenerator;
import sqlancer.tidb.mixed.TiDBDeepSeekAdvisor.Action;
import sqlancer.tidb.mixed.TiDBDeepSeekAdvisor.Stage;
import sqlancer.tidb.mixed.TiDBMixedSupport.Config;
import sqlancer.tidb.mixed.TiDBMixedSupport.Run;

class MixedUnitTest {
    private String response(String content, String finish) {
        return new Gson().toJson(
                Map.of("choices", List.of(Map.of("finish_reason", finish, "message", Map.of("content", content))),
                        "usage", Map.of("total_tokens", 25)));
    }

    private String advice(String action) {
        return "{\"action\":\"" + action + "\",\"profile\":\"SHARED\",\"reason\":\"fixture\"}";
    }

    @Test
    void structuredAdviceAndStageRestrictions() {
        assertEquals(Action.GUIDE_JOIN,
                TiDBDeepSeekAdvisor.parse(response(advice("GUIDE_JOIN"), "stop"), Stage.PREPARE).action);
        assertThrows(RuntimeException.class, () -> TiDBDeepSeekAdvisor.parse(response(" ", "stop"), Stage.DATA));
        assertThrows(RuntimeException.class, () -> TiDBDeepSeekAdvisor
                .parse(response("{\"action\":\"KEEP\",\"profile\":\"RANDOM\",\"reason\":123}", "stop"), Stage.DATA));
        assertThrows(RuntimeException.class,
                () -> TiDBDeepSeekAdvisor.parse(response(advice("KEEP"), "length"), Stage.DATA));
        assertThrows(RuntimeException.class,
                () -> TiDBDeepSeekAdvisor.parse(response(advice("PASS"), "stop"), Stage.DATA));
        assertThrows(RuntimeException.class,
                () -> TiDBDeepSeekAdvisor.parse(response(advice("GUIDE_JOIN"), "stop"), Stage.DIAGNOSE));
        assertThrows(RuntimeException.class,
                () -> TiDBDeepSeekAdvisor.parse(response(
                        "{\"action\":\"KEEP\",\"profile\":\"RANDOM\",\"reason\":\"x\",\"sql\":\"DROP TABLE t\"}",
                        "stop"), Stage.DATA));
    }

    @Test
    void retriesRespectBudgetAndSerializeRequest() {
        AtomicInteger count = new AtomicInteger();
        TiDBDeepSeekAdvisor advisor = new TiDBDeepSeekAdvisor(body -> {
            assertEquals("json_object", JsonParser.parseString(body).getAsJsonObject()
                    .getAsJsonObject("response_format").get("type").getAsString());
            assertFalse(body.contains("Bearer"));
            return count.incrementAndGet() == 1 ? new TiDBDeepSeekAdvisor.Reply(429, "busy")
                    : new TiDBDeepSeekAdvisor.Reply(200, response(advice("SET_PROFILE"), "stop"));
        }, "fixture-model", 2);
        assertEquals(Action.SET_PROFILE, advisor.suggest(Stage.DATA, Map.of("sql", "SELECT 1")).action);
        assertEquals(Action.KEEP, advisor.suggest(Stage.DATA, Map.of()).action);
        assertEquals(2, count.get());
    }

    @Test
    void authErrorDoesNotRetry() {
        AtomicInteger count = new AtomicInteger();
        TiDBDeepSeekAdvisor advisor = new TiDBDeepSeekAdvisor(body -> {
            count.incrementAndGet();
            return new TiDBDeepSeekAdvisor.Reply(401, "unauthorized");
        }, "fixture-model", 4);
        assertEquals(Action.KEEP, advisor.suggest(Stage.DATA, Map.of()).action);
        assertEquals(1, count.get());
    }

    @Test
    void perTablePlanVerification() {
        Map<String, String> assignments = new LinkedHashMap<>();
        assignments.put("t0", "tikv");
        assignments.put("t1", "tiflash");
        Config config = new Config(assignments);
        List<Map<String, String>> plan = List.of(
                Map.of("id", "TableFullScan_1", "task", "cop[tikv]", "access object", "table:t0", "actrows", "0"),
                Map.of("id", "TableFullScan_2", "task", "mpp[tiflash]", "access object", "table:t1", "actrows", "0"));
        TiDBMixedSupport.validatePlan(plan, config);
        assertThrows(TiDBMixedSupport.Rejected.class, () -> TiDBMixedSupport.validatePlan(plan.subList(0, 1), config));
        assertThrows(TiDBMixedSupport.Rejected.class,
                () -> TiDBMixedSupport.validatePlan(plan, new Config(Map.of("t0", "tiflash", "t1", "tikv"))));
        assertThrows(TiDBMixedSupport.Rejected.class,
                () -> TiDBMixedSupport.validatePlan(
                        List.of(Map.of("id", "Point_Get_1", "task", "root", "access object", "table:t0")),
                        new Config(Map.of("t0", "tikv"))));
    }

    private List<Run> runs() {
        new Randomly(5);
        List<Run> runs = new ArrayList<>();
        for (Config config : TiDBMixedSupport.configurations(List.of("t0", "t1"))) {
            Run run = new Run(config, "SELECT 1");
            run.status = "OK";
            run.columns.add("c:INT");
            run.bag.put(List.of("N:1"), 2L);
            runs.add(run);
        }
        return runs;
    }

    @Test
    void bagsAndVerdictsKeepMultiplicity() {
        List<Run> runs = runs();
        assertEquals("PASS", TiDBMixedSupport.compare(runs).status);
        runs.get(2).bag.put(List.of("N:1"), 1L);
        assertEquals("MIXED_VS_SINGLE_MISMATCH", TiDBMixedSupport.compare(runs).status);
        runs.get(0).bag.put(List.of("N:1"), 3L);
        assertEquals("BASELINE_DISAGREEMENT", TiDBMixedSupport.compare(runs).status);
        runs = runs();
        runs.get(2).status = "INVALID_CONFIGURATION";
        assertEquals("INVALID_CONFIGURATION", TiDBMixedSupport.compare(runs).status);
        assertEquals(TiDBMixedSupport.numeric("1.00"), TiDBMixedSupport.numeric("1"));
        assertFalse(TiDBMixedSupport.numeric("123456789012345678901234567890.01")
                .equals(TiDBMixedSupport.numeric("123456789012345678901234567890.02")));
    }

    static class FakeState extends TiDBGlobalState {
        FakeState() {
            setRandomly(new Randomly(42));
            setMainOptions(new MainOptions());
            setDbmsSpecificOptions(new TiDBOptions());
            List<TiDBTable> tables = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                List<TiDBColumn> columns = new ArrayList<>();
                for (int j = 0; j < 3; j++) {
                    columns.add(new TiDBColumn("c" + j, TiDBCompositeDataType.getInt(4), false, true, true));
                }
                TiDBTable table = new TiDBTable("t" + i, columns, List.of(), false);
                columns.forEach(c -> c.setTable(table));
                tables.add(table);
            }
            setSchema(new TiDBSchema(tables));
        }
    }

    @Test
    void actualSqlancerAstGenerationAndHintInvariance() throws Exception {
        FakeState state = new FakeState();
        int successful = 0;
        java.util.Set<String> queries = new java.util.HashSet<>();
        for (int i = 0; i < 200; i++) {
            try {
                TiDBMixedGeneration.Candidate q = TiDBMixedGeneration.query(state, false, new TiDBMixedSupport(state));
                assertTrue(q.tables.size() >= 2);
                queries.add(q.sql);
                for (Config config : TiDBMixedSupport
                        .configurations(q.tables.stream().map(TiDBTable::getName).collect(Collectors.toList()))) {
                    String configured = TiDBMixedSupport.configure(q.sql, config);
                    assertEquals(q.sql, configured.replaceFirst("/\\*\\+.*?\\*/ ", ""));
                }
                successful++;
            } catch (IgnoreMeException ignored) {
                // Expected upstream generation rejection, not a DBMS result.
            }
        }
        assertTrue(successful > 100);
        assertTrue(queries.size() > 100);
    }

    @Test
    void insertGeneratorReallyInvokesValueHook() {
        FakeState state = new FakeState();
        AtomicInteger calls = new AtomicInteger();
        String sql = TiDBInsertGenerator
                .getQuery(state, state.getSchema().getDatabaseTables().get(0), (column, value) -> {
                    calls.incrementAndGet();
                    return TiDBConstant.createIntConstant(7);
                }).getQueryString();
        assertTrue(calls.get() > 0);
        assertTrue(sql.contains("7"));
    }
}
