package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Evidence must preserve Java UTF-16 code units even when they are not valid Unicode scalar values. */
class MixedEvidenceSaveTest {
    @TempDir
    Path temporary;

    @Test
    void unpairedSurrogatesInSqlKeysRowsErrorsAndComplexBagKeysRoundTripExactly() throws Exception {
        String high = String.valueOf((char) 0xd800);
        String low = String.valueOf((char) 0xdc00);
        String text = "中文😀" + high + "x" + low + "\\ud800\n\"";
        JsonObject source = new JsonObject();
        source.addProperty("sql", "SELECT '" + text + "'");
        source.addProperty(high + "key" + low, text);
        source.addProperty("error", text);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("query", source);
        evidence.put("rows", List.of(List.of(text)));
        evidence.put("bag", Map.of(List.of(text), 2L));
        Path file = temporary.resolve("case.json");
        TiDBMixedSupport.save(file, evidence);
        String encoded = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(encoded.contains("\\ud800"));
        assertTrue(encoded.contains("\\udc00"));
        assertTrue(encoded.contains("中文😀"));
        JsonObject saved = JsonParser.parseString(encoded).getAsJsonObject();
        assertEquals(source, saved.getAsJsonObject("query"));
        assertEquals(text, saved.getAsJsonArray("rows").get(0).getAsJsonArray().get(0).getAsString());
        assertEquals(text,
                saved.getAsJsonArray("bag").get(0).getAsJsonArray().get(0).getAsJsonArray().get(0).getAsString());
    }

    @Test
    void everyUtf16CodeUnitAndRepeatedAtomicReplacementRoundTripExactly() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int unit = 0; unit <= Character.MAX_VALUE; unit++) {
            text.append((char) unit).append('x');
        }
        text.append("😀").append((char) 0xd800).append((char) 0xd801).append((char) 0xdc00).append((char) 0xdc01);
        Path file = temporary.resolve("case.json");
        TiDBMixedSupport.save(file, Map.of("text", "old evidence"));
        TiDBMixedSupport.save(file, Map.of("text", text.toString()));
        assertEquals(text.toString(), read(file).get("text").getAsString());
        try (Stream<Path> files = Files.list(temporary)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void serializationFailurePreservesOldCaseAndLeavesSafeDiagnosticWithOriginalStatus() throws Exception {
        Path file = temporary.resolve("case.json");
        TiDBMixedSupport.save(file, Map.of("status", "OLD_EVIDENCE"));
        String sql = "SELECT '" + (char) 0xd800 + "'";
        Map<String, Object> invalid = Map.of("status", "MIXED_VS_MIXED_MISMATCH", "attempts",
                List.of(Map.of("sql", sql, "snapshot", "470548885236613124", "revision", 1)), "bad", Double.NaN);
        assertThrows(IllegalArgumentException.class, () -> TiDBMixedSupport.save(file, invalid));
        assertEquals("OLD_EVIDENCE", read(file).get("status").getAsString());
        JsonObject diagnostic = read(temporary.resolve("case.json.save-error.json"));
        assertEquals("EVIDENCE_SAVE_ERROR", diagnostic.get("status").getAsString());
        assertEquals("serialize", diagnostic.get("stage").getAsString());
        assertEquals("MIXED_VS_MIXED_MISMATCH", diagnostic.get("original_status").getAsString());
        assertEquals(sql, diagnostic.get("original_sql").getAsString());
        assertEquals("470548885236613124", diagnostic.get("original_snapshot").getAsString());
        assertEquals("1", diagnostic.get("original_revision").getAsString());
        assertFalse(diagnostic.has("recovery_file"));
    }

    @Test
    void replacementFailureSavesFullRecoveryAndMinimumDiagnosticWithoutTemporaryLeaks() throws Exception {
        Path file = block("case.json");
        String sql = "SELECT '" + (char) 0xdc00 + "'";
        Map<String, Object> evidence = Map.of("status", "MIXED_VS_MIXED_MISMATCH", "rows",
                List.of("中文" + (char) 0xd800), "snapshot", "470548885236613124", "attempts",
                List.of(Map.of("sql", sql)));
        JsonObject source = new Gson().toJsonTree(evidence).getAsJsonObject();
        assertThrows(IOException.class, () -> TiDBMixedSupport.save(file, source));
        JsonObject diagnostic = read(temporary.resolve("case.json.save-error.json"));
        assertEquals("write_or_replace", diagnostic.get("stage").getAsString());
        assertEquals("MIXED_VS_MIXED_MISMATCH", diagnostic.get("original_status").getAsString());
        assertEquals(sql, diagnostic.get("original_sql").getAsString());
        JsonObject recovery = read(temporary.resolve(diagnostic.get("recovery_file").getAsString()));
        assertEquals(source, recovery);
        assertEquals("中文" + (char) 0xd800, recovery.getAsJsonArray("rows").get(0).getAsString());
        assertEquals("470548885236613124", recovery.get("snapshot").getAsString());
        assertEquals("untouched", Files.readString(file.resolve("sentinel")));
        try (Stream<Path> files = Files.list(temporary)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void unwritableDirectoryLeavesUnicodeSafeMinimumRecordInProcessLog() throws Exception {
        Path blocked = temporary.resolve("blocked");
        Files.writeString(blocked, "parent is a file");
        ByteArrayOutputStream logged = new ByteArrayOutputStream();
        PrintStream previous = System.err;
        try (PrintStream capture = new PrintStream(logged, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            assertThrows(IOException.class, () -> TiDBMixedSupport.save(blocked.resolve("case.json"),
                    Map.of("status", "PASS", "sql", "SELECT '" + (char) 0xdc00 + "'")));
        } finally {
            System.setErr(previous);
        }
        JsonObject diagnostic = JsonParser.parseString(logged.toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("EVIDENCE_SAVE_ERROR", diagnostic.get("status").getAsString());
        assertEquals("SELECT '" + (char) 0xdc00 + "'", diagnostic.get("original_sql").getAsString());
        assertTrue(diagnostic.has("recovery_error"));
        assertTrue(diagnostic.has("error_record_error"));
    }

    @Test
    void finalSaveWithSurrogatesPreservesPassAndPreflightEvidence() throws Exception {
        Map<String, Object> report = report("DATA_PREFLIGHT_MISMATCH");
        List<Map<String, String>> failures = new ArrayList<>();
        report.put("evidenceSaveErrors", failures);
        assertTrue(finish(report, failures, true, false));
        assertTrue(failures.isEmpty());
        assertEquals(read(temporary.resolve("case.json")), read(temporary.resolve("preflight-mismatch.json")));
        Map<String, Object> pass = report("PASS");
        assertTrue(finish(pass, failures, false, false));
        assertEquals("PASS", read(temporary.resolve("case.json")).get("status").getAsString());
    }

    @Test
    void auxiliarySaveFailureCannotLeavePassAndStillAttemptsRemainingEvidence() throws Exception {
        block("history.json");
        Map<String, Object> report = report("PASS");
        List<Map<String, String>> failures = new ArrayList<>();
        report.put("evidenceSaveErrors", failures);
        assertFalse(finish(report, failures, false, false));
        JsonObject saved = read(temporary.resolve("case.json"));
        assertEquals("EVIDENCE_SAVE_ERROR", saved.get("status").getAsString());
        assertEquals(1, saved.getAsJsonArray("evidenceSaveErrors").size());
        assertTrue(Files.isRegularFile(temporary.resolve("coverage.json")));
        assertTrue(Files.isRegularFile(temporary.resolve("preflight-counts.json")));
    }

    @Test
    void caseSaveFailureReturnsNormallyAndPreservesMismatchInRecoveryThenCanSaveNextCase() throws Exception {
        Path blocked = block("case.json");
        Map<String, Object> report = report("MIXED_VS_MIXED_MISMATCH");
        List<Map<String, String>> failures = new ArrayList<>();
        report.put("evidenceSaveErrors", failures);
        assertFalse(finish(report, failures, false, true));
        assertEquals("MIXED_VS_MIXED_MISMATCH", report.get("status"));
        assertEquals(1, failures.size());
        JsonObject diagnostic = read(temporary.resolve("case.json.save-error.json"));
        JsonObject recovered = read(temporary.resolve(diagnostic.get("recovery_file").getAsString()));
        assertEquals("MIXED_VS_MIXED_MISMATCH", recovered.get("status").getAsString());
        assertEquals("left=" + (char) 0xd800 + ",right=" + (char) 0xdc00,
                recovered.getAsJsonArray("runs").get(0).getAsString());
        assertTrue(Files.isRegularFile(temporary.resolve("coverage.json")));
        Files.delete(blocked.resolve("sentinel"));
        Files.delete(blocked);
        assertTrue(finish(report("PASS"), new ArrayList<>(), false, false));
        assertEquals("PASS", read(temporary.resolve("case.json")).get("status").getAsString());
    }

    private Path block(String name) throws IOException {
        Path directory = Files.createDirectory(temporary.resolve(name));
        Files.writeString(directory.resolve("sentinel"), "untouched");
        return directory;
    }

    private static Map<String, Object> report(String status) {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("status", status);
        report.put("sql", "SELECT '" + (char) 0xd800 + "'");
        report.put("runs", List.of("left=" + (char) 0xd800 + ",right=" + (char) 0xdc00));
        return report;
    }

    private boolean finish(Map<String, Object> report, List<Map<String, String>> failures, boolean preflight,
            boolean mismatch) {
        return TiDBAiMixedOracle.saveFinalEvidence(temporary, report, Map.of("PASS", 1L), Map.of("mismatch", 1L),
                Map.of("valid", 4L), preflight, mismatch, failures);
    }

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }
}
