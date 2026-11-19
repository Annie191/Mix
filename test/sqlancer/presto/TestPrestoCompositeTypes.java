package sqlancer.presto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.beust.jcommander.JCommander;

import sqlancer.MainOptions;
import sqlancer.Randomly;
import sqlancer.presto.PrestoSchema.PrestoColumn;
import sqlancer.presto.PrestoSchema.PrestoCompositeDataType;
import sqlancer.presto.PrestoSchema.PrestoDataType;
import sqlancer.presto.ast.PrestoAggregateFunction;
import sqlancer.presto.ast.PrestoColumnReference;
import sqlancer.presto.ast.PrestoExpression;
import sqlancer.presto.gen.PrestoTypedExpressionGenerator;

public class TestPrestoCompositeTypes {

    @Test
    void arrayAggregatesPreserveElementType() {
        PrestoTypedExpressionGenerator generator = generator();
        PrestoCompositeDataType timeArray = array(new PrestoCompositeDataType(PrestoDataType.TIME, 0, 0));
        for (int i = 0; i < 100; i++) {
            for (PrestoAggregateFunction function : List.of(PrestoAggregateFunction.MAX, PrestoAggregateFunction.MIN)) {
                String argument = PrestoToStringVisitor
                        .asString(generator.generateArgsForAggregate(timeArray, function).get(0));
                assertTrue(argument.startsWith("ARRAY["), argument);
                assertTrue(argument.contains("TIME '"), argument);
            }
        }
    }

    @Test
    void columnSelectionDistinguishesNestedArrayTypes() {
        PrestoTypedExpressionGenerator generator = generator();
        PrestoCompositeDataType requested = array(array(new PrestoCompositeDataType(PrestoDataType.TIME, 0, 0)));
        PrestoColumn wrong = new PrestoColumn("decimal_arrays",
                array(array(new PrestoCompositeDataType(PrestoDataType.DECIMAL, 8, 4))), false, true);
        PrestoColumn matching = new PrestoColumn("time_arrays",
                array(array(new PrestoCompositeDataType(PrestoDataType.TIME, 0, 0))), false, true);
        generator.setColumns(List.of(wrong, matching));
        int columns = 0;
        for (int i = 0; i < 100; i++) {
            PrestoExpression expression = generator.generateLeafNode(requested);
            if (expression instanceof PrestoColumnReference) {
                assertEquals(matching, ((PrestoColumnReference) expression).getColumn());
                columns++;
            }
        }
        assertTrue(columns > 0);
        generator.setColumns(List.of(wrong));
        for (int i = 0; i < 100; i++) {
            assertFalse(generator.generateLeafNode(requested) instanceof PrestoColumnReference);
        }
    }

    private static PrestoCompositeDataType array(PrestoCompositeDataType element) {
        return new PrestoCompositeDataType(PrestoDataType.ARRAY, element);
    }

    private static PrestoTypedExpressionGenerator generator() {
        MainOptions options = new MainOptions();
        JCommander.newBuilder().addObject(options).build().parse("--max-expression-depth", "0");
        PrestoGlobalState state = new PrestoGlobalState();
        state.setMainOptions(options);
        state.setDbmsSpecificOptions(new PrestoOptions());
        state.setRandomly(new Randomly(0));
        return new PrestoTypedExpressionGenerator(state);
    }
}
