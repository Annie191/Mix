package sqlancer.qpg.materialize;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import sqlancer.Main;
import sqlancer.MainOptions;
import sqlancer.ComparatorHelper;
import sqlancer.IgnoreMeException;
import sqlancer.SQLConnection;
import sqlancer.common.query.ExpectedErrors;
import sqlancer.common.query.SQLQueryAdapter;
import sqlancer.materialize.MaterializeOptions;
import sqlancer.materialize.MaterializeGlobalState;
import sqlancer.materialize.MaterializeProvider;

public class TestMaterializeQueryPlan {

    @Test
    void testOversizedResultIsSkippedWithoutTruncatingComparison() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("MATERIALIZE_AVAILABLE")));
        MaterializeGlobalState state = createState("resultbudget");
        try (SQLConnection connection = state.getConnection()) {
            assertEquals(List.of("8MB"), ComparatorHelper.getResultSetFirstColumnAsString("SHOW max_query_result_size",
                    new ExpectedErrors(), state));
            assertThrows(IgnoreMeException.class,
                    () -> ComparatorHelper.getResultSetFirstColumnAsString("SELECT repeat('x', 8388609)",
                            ExpectedErrors.from("result exceeds max size of"), state));
            assertEquals(List.of("ok"),
                    ComparatorHelper.getResultSetFirstColumnAsString("SELECT 'ok'", new ExpectedErrors(), state));
        }
    }

    private static MaterializeGlobalState createState(String databaseName) throws Exception {
        MaterializeProvider provider = new MaterializeProvider();
        MaterializeGlobalState state = provider.getGlobalStateClass().getDeclaredConstructor().newInstance();
        state.setDbmsSpecificOptions(provider.getOptionClass().getDeclaredConstructor().newInstance());
        state.setDatabaseName(databaseName);
        MainOptions options = new MainOptions();
        state.setMainOptions(options);
        state.setState(provider.getStateToReproduce(databaseName));
        state.setStateLogger(new Main.StateLogger(databaseName, provider, options));
        state.setConnection(provider.createDatabase(state));
        return state;
    }

    @Test
    void testMaterializeQueryPlan() throws Exception {
        String materialize = System.getenv("MATERIALIZE_AVAILABLE");
        boolean materializeIsAvailable = materialize != null && materialize.equalsIgnoreCase("true");
        assumeTrue(materializeIsAvailable);

        String databaseName = "queryplan";
        MaterializeProvider provider = new MaterializeProvider();
        MaterializeGlobalState state = provider.getGlobalStateClass().getDeclaredConstructor().newInstance();
        MaterializeOptions materializeOption = provider.getOptionClass().getDeclaredConstructor().newInstance();
        state.setDbmsSpecificOptions(materializeOption);
        state.setDatabaseName(databaseName);
        MainOptions options = new MainOptions();
        state.setMainOptions(options);
        state.setState(provider.getStateToReproduce(databaseName));
        SQLConnection con = provider.createDatabase(state);
        state.setConnection(con);
        Main.StateLogger logger = new Main.StateLogger(databaseName, provider, options);
        state.setStateLogger(logger);

        SQLQueryAdapter q = new SQLQueryAdapter("CREATE TABLE t1(a INT, b INT);", true);
        q.execute(state);
        q = new SQLQueryAdapter("CREATE TABLE t2(c INT);", true);
        q.execute(state);
        String queryPlan = provider.getQueryPlan("SELECT * FROM t1 RIGHT JOIN t2 ON a<>0;", state);

        assertEquals(
                "With;ReadStorage queryplan.public.t1 // { arity: 2 };ReadStorage queryplan.public.t2 // { arity: 1 };Return // { arity: 3 };Union // { arity: 3 };Get l0 // { arity: 3 };Project (#2, #3, #0{c}) // { arity: 3 };Union // { arity: 1 };Negate // { arity: 1 };Project (#2{c}) // { arity: 1 };ReadStorage queryplan.public.t2 // { arity: 1 };ReadStorage queryplan.public.t2 // { arity: 1 };;Source queryplan.public.t1;Source queryplan.public.t2;;Target cluster: quickstart;",
                queryPlan);
    }

}
