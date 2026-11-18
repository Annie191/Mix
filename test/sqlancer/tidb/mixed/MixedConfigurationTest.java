package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import sqlancer.Randomly;
import sqlancer.tidb.mixed.TiDBMixedSupport.Config;
import sqlancer.tidb.mixed.TiDBMixedSupport.Outcome;
import sqlancer.tidb.mixed.TiDBMixedSupport.Run;

class MixedConfigurationTest {
    private List<String> tables(int count) {
        return IntStream.range(0, count).mapToObj(i -> "t" + i).collect(Collectors.toList());
    }

    private Set<String> names(List<Config> configs) {
        return configs.stream().map(c -> c.name).collect(Collectors.toSet());
    }

    private List<Run> runs(int tableCount) {
        List<Run> runs = new ArrayList<>();
        for (Config config : TiDBMixedSupport.configurations(tables(tableCount))) {
            Run run = new Run(config, "SELECT 1");
            run.snapshot = "123456789";
            run.status = "OK";
            run.columns.add("c:INT");
            run.bag.put(List.of("N:1"), 1L);
            runs.add(run);
        }
        return runs;
    }

    @Test
    void enumerationContainsEveryBinaryAssignmentExactlyOnce() {
        for (int count = 2; count <= 5; count++) {
            List<String> tables = tables(count);
            List<Config> all = TiDBMixedSupport.enumerateConfigurations(tables);
            assertEquals(1 << count, all.size());
            assertEquals(all.size(), names(all).size());
            Set<String> masks = all.stream().map(c -> tables.stream()
                    .map(t -> c.engines.get(t).equals("tikv") ? "0" : "1").collect(Collectors.joining()))
                    .collect(Collectors.toSet());
            for (int mask = 0; mask < 1 << count; mask++) {
                String binary = Integer.toBinaryString(mask);
                assertTrue(masks.contains("0".repeat(count - binary.length()) + binary));
            }
        }
    }

    @Test
    void twoAndThreeTablesExecuteAllAssignments() {
        for (int count : List.of(2, 3)) {
            List<Config> selected = TiDBMixedSupport.configurations(tables(count));
            assertEquals(1 << count, selected.size());
            assertEquals(names(TiDBMixedSupport.enumerateConfigurations(tables(count))), names(selected));
            assertEquals(2, selected.stream().filter(c -> !c.mixed()).count());
        }
    }

    @Test
    void largerQueriesSampleFourDistinctMixedAssignmentsAndBothBaselines() {
        Set<String> sampledSets = new HashSet<>();
        boolean balancedAssignment = false;
        for (int count : List.of(4, 5)) {
            List<String> tables = tables(count);
            Set<String> all = names(TiDBMixedSupport.enumerateConfigurations(tables));
            for (int seed = 0; seed < 30; seed++) {
                new Randomly(seed);
                List<Config> selected = TiDBMixedSupport.configurations(tables);
                assertEquals(6, selected.size());
                assertEquals(6, names(selected).size());
                assertEquals(4, selected.stream().filter(Config::mixed).count());
                assertTrue(all.containsAll(names(selected)));
                assertTrue(selected.stream().anyMatch(c -> c.engines.values().stream().allMatch("tikv"::equals)));
                assertTrue(selected.stream().anyMatch(c -> c.engines.values().stream().allMatch("tiflash"::equals)));
                sampledSets.add(new java.util.TreeSet<>(names(selected)).toString());
                if (count == 4) {
                    balancedAssignment |= selected.stream()
                            .anyMatch(c -> c.engines.values().stream().filter("tikv"::equals).count() == 2);
                }
            }
        }
        assertTrue(sampledSets.size() > 2);
        assertTrue(balancedAssignment);
    }

    @Test
    void completeComparisonsFollowFourEightAndSixPathBudgets() {
        for (int count = 2; count <= 5; count++) {
            List<Run> runs = runs(count);
            Outcome outcome = TiDBMixedSupport.compare(runs);
            int expected = count == 2 ? 4 : count == 3 ? 8 : 6;
            assertEquals(expected, runs.size());
            assertEquals("PASS", outcome.status);
            assertTrue(outcome.allPathsValid);
            assertFalse(outcome.mismatch());
            assertEquals(expected * (expected - 1) / 2, outcome.comparisons.size());
        }
    }

    @Test
    void expandedPathSetsRejectMissingDuplicateOrFailedPaths() {
        for (int count : List.of(3, 4, 5)) {
            List<Run> runs = runs(count);
            Outcome missing = TiDBMixedSupport.compare(runs.subList(0, runs.size() - 1));
            assertEquals("INVALID_CONFIGURATION", missing.status);
            assertFalse(missing.allPathsValid);
            Run last = runs.get(runs.size() - 1);
            runs.set(runs.size() - 1, runs.get(2));
            Outcome duplicate = TiDBMixedSupport.compare(runs);
            assertEquals("INVALID_CONFIGURATION", duplicate.status);
            assertFalse(duplicate.allPathsValid);
            runs.set(runs.size() - 1, last);
            runs.get(0).status = "EXECUTION_ERROR";
            Outcome failed = TiDBMixedSupport.compare(runs);
            assertEquals("EXECUTION_ERROR", failed.status);
            assertFalse(failed.allPathsValid);
        }
    }

    @Test
    void expandedMixedDifferencesSurviveFailedBaselinesAndConfirmTwice() {
        for (int count : List.of(3, 4, 5)) {
            List<Run> runs = runs(count);
            runs.get(0).status = "EXECUTION_ERROR";
            runs.get(1).status = "EXECUTION_ERROR";
            runs.get(2).bag.put(List.of("N:1"), 2L);
            Outcome outcome = TiDBMixedSupport.compare(runs);
            assertEquals("MIXED_VS_MIXED_MISMATCH", outcome.status);
            assertTrue(outcome.mismatch());
            assertFalse(outcome.allPathsValid);
            assertEquals(runs.size() - 3, outcome.differingPairs.size());
            assertTrue(TiDBAiMixedOracle.confirmed(outcome, List.of(outcome, outcome)));
        }
    }

    @Test
    void enumerationRejectsTooFewOrDuplicateTables() {
        assertThrows(IllegalArgumentException.class, () -> TiDBMixedSupport.enumerateConfigurations(List.of()));
        assertThrows(IllegalArgumentException.class, () -> TiDBMixedSupport.enumerateConfigurations(List.of("t0")));
        assertThrows(IllegalArgumentException.class,
                () -> TiDBMixedSupport.enumerateConfigurations(List.of("t0", "t0")));
    }
}
