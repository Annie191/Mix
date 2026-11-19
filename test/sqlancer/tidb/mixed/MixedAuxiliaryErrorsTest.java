package sqlancer.tidb.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import sqlancer.tidb.ast.TiDBJoin;

class MixedAuxiliaryErrorsTest {
    @TempDir
    Path directory;

    @Test
    void internalObservationArchivesActualDataAndReportsThroughSqlancerIndependently() throws Exception {
        try (MixedObservationTest.Fixture fixture = new MixedObservationTest.Fixture()) {
            TiDBMixedAuxiliaryErrors archive = new TiDBMixedAuxiliaryErrors(directory);
            fixture.state.setAuxiliaryErrors(archive);
            fixture.archiveMetadata = true;
            fixture.probeError = new SQLException("runtime error: invalid memory address or nil pointer dereference",
                    "HY000", 1105);
            TiDBMixedObservations facts = new TiDBMixedObservations(fixture.query(TiDBJoin.JoinType.RIGHT), "q", 2, 7);
            facts.collect(fixture.io, List.of("query_input_before"), new TiDBMixedObservations.Budget(1, 64, 10_000));
            JsonObject record = JsonParser.parseString(Files.readString(directory.resolve("error-0.json")))
                    .getAsJsonObject();
            assertEquals("INTERNAL_ERROR_CANDIDATE", record.get("status").getAsString());
            assertEquals("1234567", record.get("snapshot").getAsString());
            assertEquals(7, record.get("data_revision").getAsInt());
            assertEquals("HY000", record.get("sqlstate").getAsString());
            assertEquals(1105, record.get("error_code").getAsInt());
            assertEquals("CAPTURED", record.get("replay_status").getAsString());
            assertEquals(9, record.getAsJsonArray("restoreStatements").size());
            assertTrue(record.getAsJsonArray("bindings").isEmpty());
            assertFalse(record.get("confirmed").getAsBoolean());
            assertTrue(Files.exists(directory.resolve("index.json")));
            assertThrows(AssertionError.class, archive::reportToSqlancer);
            assertEquals("", fixture.snapshot);
            assertEquals("tikv,tiflash", fixture.engines);
            fixture.probeError = null;
            assertEquals(1, TiDBMixedReadOnly.read(fixture.io, "SELECT 1", 1, 5).rows.size());
        }
    }

    @Test
    void ordinaryReadFailureIsSavedWithoutDeclaringBug() throws Exception {
        try (MixedObservationTest.Fixture fixture = new MixedObservationTest.Fixture()) {
            TiDBMixedAuxiliaryErrors archive = new TiDBMixedAuxiliaryErrors(directory);
            fixture.state.setAuxiliaryErrors(archive);
            fixture.failure = true;
            TiDBMixedObservations facts = new TiDBMixedObservations(fixture.query(TiDBJoin.JoinType.INNER), "q", 0, 0);
            facts.collect(fixture.io, List.of("rows_t0"), new TiDBMixedObservations.Budget(1, 64, 10_000));
            JsonObject record = JsonParser.parseString(Files.readString(directory.resolve("error-0.json")))
                    .getAsJsonObject();
            assertEquals("OBSERVATION_READ_ERROR", record.get("status").getAsString());
            assertEquals(0, archive.summary.get("internal_candidates"));
            archive.reportToSqlancer();
        }
    }

    @Test
    void failedDumpKeepsOriginalErrorAndNestedInternalExceptionsAreRecognized() throws Exception {
        try (MixedObservationTest.Fixture fixture = new MixedObservationTest.Fixture()) {
            TiDBMixedAuxiliaryErrors archive = new TiDBMixedAuxiliaryErrors(directory);
            fixture.state.setAuxiliaryErrors(archive);
            SQLException error = new SQLException("wrapper", "HY000", 1105);
            error.setNextException(new SQLException("runtime error: nil pointer", "HY000", 1105));
            fixture.probeError = error;
            TiDBMixedObservations facts = new TiDBMixedObservations(fixture.query(TiDBJoin.JoinType.INNER), "q", 0, 0);
            facts.collect(fixture.io, List.of("rows_t0"), new TiDBMixedObservations.Budget(1, 64, 10_000));
            JsonObject record = JsonParser.parseString(Files.readString(directory.resolve("error-0.json")))
                    .getAsJsonObject();
            assertEquals("CAPTURE_FAILED", record.get("replay_status").getAsString());
            assertEquals("wrapper",
                    record.getAsJsonArray("exception_chain").get(0).getAsJsonObject().get("message").getAsString());
            assertEquals(2, record.getAsJsonArray("exception_chain").size());
            assertTrue(record.get("sql").getAsString().startsWith("SELECT COUNT"));
            assertThrows(AssertionError.class, archive::reportToSqlancer);
        }
    }
}
