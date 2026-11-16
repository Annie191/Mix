package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import sqlancer.tidb.mixed.TiDBMixedSupport.Config;
import sqlancer.tidb.mixed.TiDBMixedSupport.Outcome;
import sqlancer.tidb.mixed.TiDBMixedSupport.Run;

class MixedComparisonTest {
    private List<Run> runs() {
        List<Run> runs = new ArrayList<>();
        for (Config config : TiDBMixedSupport.configurations(List.of("t0", "t1"))) {
            Run run = new Run(config, "SELECT c FROM t0,t1");
            run.status = "OK";
            run.columns.add("c:INT");
            run.bag.put(List.of("N:1"), 1L);
            runs.add(run);
        }
        return runs;
    }

    private List<Run> mixedOnlyDisagreement() {
        List<Run> runs = runs();
        runs.get(0).status = "EXECUTION_ERROR";
        runs.get(1).status = "EXECUTION_ERROR";
        runs.get(3).bag.clear();
        runs.get(3).bag.put(List.of("N:2"), 1L);
        return runs;
    }

    @Test
    void differentMixedResultsAreReportedWhenBothBaselinesFail() {
        Outcome outcome = TiDBMixedSupport.compare(mixedOnlyDisagreement());
        assertEquals("MIXED_VS_MIXED_MISMATCH", outcome.status);
        assertEquals(1, outcome.comparisons.size());
        assertEquals(2, outcome.failures.size());
        assertTrue(outcome.mismatch());
        assertFalse(outcome.allPathsValid);
        assertEquals(Set.of("MIXED_VS_MIXED_MISMATCH"), outcome.mismatchTypes);
        assertEquals("MIXED_VS_MIXED_MISMATCH", outcome.comparisons.get(0).get("mismatchType"));
    }

    @Test
    void agreeingMixedResultsWithFailedBaselinesAreNotAPass() {
        List<Run> runs = runs();
        runs.get(0).status = "EXECUTION_ERROR";
        runs.get(1).status = "EXECUTION_ERROR";
        Outcome outcome = TiDBMixedSupport.compare(runs);
        assertEquals("EXECUTION_ERROR", outcome.status);
        assertEquals(1, outcome.comparisons.size());
        assertEquals(2, outcome.failures.size());
        assertFalse(outcome.mismatch());
        assertFalse(outcome.allPathsValid);
        assertTrue(outcome.mismatchTypes.isEmpty());
    }

    @Test
    void oneValidPathCannotEstablishAgreementOrDisagreement() {
        List<Run> runs = runs();
        runs.subList(0, 3).forEach(run -> run.status = "INCONCLUSIVE");
        Outcome outcome = TiDBMixedSupport.compare(runs);
        assertEquals("INCONCLUSIVE", outcome.status);
        assertTrue(outcome.comparisons.isEmpty());
        assertEquals(3, outcome.failures.size());
        assertFalse(outcome.mismatch());
        assertFalse(outcome.allPathsValid);
    }

    @Test
    void allFourValidPathsProduceSixComparisons() {
        Outcome outcome = TiDBMixedSupport.compare(runs());
        assertEquals("PASS", outcome.status);
        assertTrue(outcome.allPathsValid);
        assertFalse(outcome.mismatch());
        assertTrue(outcome.failures.isEmpty());
        assertEquals(6, outcome.comparisons.size());
        assertEquals(1, outcome.comparisons.stream()
                .filter(pair -> pair.get("mismatchType").equals("BASELINE_DISAGREEMENT")).count());
        assertEquals(4, outcome.comparisons.stream()
                .filter(pair -> pair.get("mismatchType").equals("MIXED_VS_SINGLE_MISMATCH")).count());
        assertEquals(1, outcome.comparisons.stream()
                .filter(pair -> pair.get("mismatchType").equals("MIXED_VS_MIXED_MISMATCH")).count());
        assertTrue(outcome.comparisons.stream().allMatch(pair -> Boolean.TRUE.equals(pair.get("equal"))));
    }

    @Test
    void primaryStatusKeepsPriorityWithoutDroppingOtherDiscrepancies() {
        List<Run> runs = runs();
        for (int i = 0; i < runs.size(); i++) {
            runs.get(i).bag.clear();
            runs.get(i).bag.put(List.of("N:" + i), 1L);
        }
        Outcome outcome = TiDBMixedSupport.compare(runs);
        assertEquals("BASELINE_DISAGREEMENT", outcome.status);
        assertEquals(Set.of("BASELINE_DISAGREEMENT", "MIXED_VS_SINGLE_MISMATCH", "MIXED_VS_MIXED_MISMATCH"),
                outcome.mismatchTypes);
        assertEquals(6, outcome.differingPairs.size());
        assertEquals(6, outcome.comparisons.size());
        assertTrue(outcome.allPathsValid);
        assertTrue(outcome.mismatch());

        runs.get(1).bag.clear();
        runs.get(1).bag.putAll(runs.get(0).bag);
        outcome = TiDBMixedSupport.compare(runs);
        assertEquals("MIXED_VS_SINGLE_MISMATCH", outcome.status);
        assertEquals(Set.of("MIXED_VS_SINGLE_MISMATCH", "MIXED_VS_MIXED_MISMATCH"), outcome.mismatchTypes);
        assertEquals(5, outcome.differingPairs.size());
    }

    @Test
    void missingOrDuplicateConfigurationsPreventPassDespiteAgreement() {
        List<Run> runs = runs();
        for (List<Run> partial : List.of(List.<Run> of(), runs.subList(0, 1), runs.subList(0, 3), runs.subList(2, 4))) {
            Outcome outcome = TiDBMixedSupport.compare(partial);
            assertEquals("INVALID_CONFIGURATION", outcome.status);
            assertFalse(outcome.allPathsValid);
            assertFalse(outcome.mismatch());
        }
        runs.set(3, runs.get(2));
        Outcome outcome = TiDBMixedSupport.compare(runs);
        assertEquals("INVALID_CONFIGURATION", outcome.status);
        assertFalse(outcome.allPathsValid);
        assertFalse(outcome.mismatch());
    }

    @Test
    void mixedDisagreementTakesPrecedenceOverEveryFailedPathStatus() {
        for (String failure : List.of("EXECUTION_ERROR", "INCONCLUSIVE", "INVALID_CONFIGURATION")) {
            List<Run> runs = mixedOnlyDisagreement();
            runs.get(0).status = failure;
            runs.get(1).status = failure;
            Outcome outcome = TiDBMixedSupport.compare(runs);
            assertEquals("MIXED_VS_MIXED_MISMATCH", outcome.status);
            assertEquals(2, outcome.failures.size());
            assertTrue(outcome.mismatch());
            assertFalse(outcome.allPathsValid);
        }
    }

    @Test
    void mixedComparisonsRetainMetadataAndDuplicateMultiplicity() {
        List<Run> runs = runs();
        runs.get(0).status = "EXECUTION_ERROR";
        runs.get(1).status = "EXECUTION_ERROR";
        runs.get(3).columns.set(0, "c:VARCHAR");
        assertEquals("MIXED_VS_MIXED_MISMATCH", TiDBMixedSupport.compare(runs).status);
        runs.get(3).columns.set(0, "c:INT");
        runs.get(3).bag.put(List.of("N:1"), 2L);
        assertEquals("MIXED_VS_MIXED_MISMATCH", TiDBMixedSupport.compare(runs).status);
    }

    @Test
    void differencesAndConfirmationAreIndependentOfExecutionOrder() {
        List<Run> runs = mixedOnlyDisagreement();
        Outcome expected = TiDBMixedSupport.compare(runs);
        Collections.reverse(runs);
        Outcome repeated = TiDBMixedSupport.compare(runs);
        assertEquals(expected.signature(), repeated.signature());
        assertTrue(TiDBAiMixedOracle.confirmed(expected, List.of(repeated, repeated)));
        assertFalse(TiDBAiMixedOracle.confirmed(expected, List.of(repeated)));
        assertFalse(TiDBAiMixedOracle.confirmed(expected, List.of()));
        Outcome pass = TiDBMixedSupport.compare(runs());
        assertFalse(TiDBAiMixedOracle.confirmed(pass, List.of(pass, pass)));
    }

    @Test
    void recoveringBaselinesDoNotPreventConfirmationOfTheOriginalMixedPair() {
        List<Run> runs = mixedOnlyDisagreement();
        Outcome expected = TiDBMixedSupport.compare(runs);
        runs.get(0).status = "OK";
        runs.get(1).status = "OK";
        Outcome recovered = TiDBMixedSupport.compare(runs);
        assertEquals("MIXED_VS_SINGLE_MISMATCH", recovered.status);
        assertTrue(recovered.allPathsValid);
        assertTrue(TiDBAiMixedOracle.confirmed(expected, List.of(expected, recovered)));
    }

    @Test
    void failingOrAgreeingTargetPairsCannotBeReplacedByOtherDiscrepancies() {
        List<Run> runs = mixedOnlyDisagreement();
        Outcome expected = TiDBMixedSupport.compare(runs);
        runs.get(0).status = "OK";
        runs.get(0).bag.clear();
        runs.get(0).bag.put(List.of("N:3"), 1L);
        runs.get(3).status = "EXECUTION_ERROR";
        Outcome failedTarget = TiDBMixedSupport.compare(runs);
        assertTrue(failedTarget.mismatch());
        assertFalse(TiDBAiMixedOracle.confirmed(expected, List.of(expected, failedTarget)));

        runs.get(3).status = "OK";
        runs.get(3).bag.clear();
        runs.get(3).bag.putAll(runs.get(2).bag);
        Outcome agreeingTarget = TiDBMixedSupport.compare(runs);
        assertTrue(agreeingTarget.mismatch());
        assertFalse(TiDBAiMixedOracle.confirmed(expected, List.of(expected, agreeingTarget)));
    }

    @Test
    void confirmationRequiresEveryOriginalDifferingPairInBothRepetitions() {
        List<Run> runs = runs();
        runs.get(3).bag.clear();
        runs.get(3).bag.put(List.of("N:2"), 1L);
        Outcome expected = TiDBMixedSupport.compare(runs);
        assertEquals(3, expected.differingPairs.size());
        runs.get(0).status = "EXECUTION_ERROR";
        Outcome partial = TiDBMixedSupport.compare(runs);
        assertTrue(partial.mismatch());
        assertFalse(TiDBAiMixedOracle.confirmed(expected, List.of(partial, expected)));
    }
}
