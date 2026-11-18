package sqlancer.tidb.mixed;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Lossless JSON persistence for Java strings, including isolated UTF-16 surrogate code units. */
final class TiDBMixedEvidence {
    private TiDBMixedEvidence() {
    }

    private static String json(Object value) {
        String serialized = new GsonBuilder().setPrettyPrinting().serializeNulls().enableComplexMapKeySerialization()
                .create().toJson(value);
        StringBuilder encoded = new StringBuilder(serialized.length());
        for (int i = 0; i < serialized.length(); i++) {
            char current = serialized.charAt(i);
            if (Character.isHighSurrogate(current) && i + 1 < serialized.length()
                    && Character.isLowSurrogate(serialized.charAt(i + 1))) {
                encoded.append(current).append(serialized.charAt(++i));
            } else if (Character.isSurrogate(current)) {
                encoded.append("\\u");
                for (int shift = 12; shift >= 0; shift -= 4) {
                    encoded.append(Character.forDigit((current >> shift) & 15, 16));
                }
            } else {
                encoded.append(current);
            }
        }
        return encoded.toString();
    }

    private static void writeAtomic(Path target, String json) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".mixed-", ".json.tmp");
        Exception failure = null;
        try {
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException error) {
            failure = error;
            throw error;
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException | RuntimeException cleanup) {
                if (failure == null) {
                    throw cleanup;
                }
                failure.addSuppressed(cleanup);
            }
        }
    }

    private static Object field(Object value, String name) {
        return value instanceof Map<?, ?> ? ((Map<?, ?>) value).get(name)
                : value instanceof JsonObject ? ((JsonObject) value).get(name) : null;
    }

    private static void copyFields(JsonObject record, Object value) {
        for (String field : List.of("status", "database", "sql", "snapshot", "tidbCommit", "seed", "revision")) {
            if (record.has("original_" + field)) {
                continue;
            }
            Object original = field(value, field);
            if (original instanceof String || original instanceof Number || original instanceof Boolean) {
                record.addProperty("original_" + field, original.toString());
            } else if (original instanceof JsonElement && ((JsonElement) original).isJsonPrimitive()) {
                record.addProperty("original_" + field, ((JsonElement) original).getAsString());
            }
        }
    }

    private static void originalFields(JsonObject record, Object value) {
        copyFields(record, value);
        Object attempts = field(value, "attempts");
        // Production reports keep SQL and snapshot in the current attempt, not at the root.
        if (attempts instanceof List<?> && !((List<?>) attempts).isEmpty()) {
            List<?> list = (List<?>) attempts;
            copyFields(record, list.get(list.size() - 1));
        } else if (attempts instanceof JsonElement && ((JsonElement) attempts).isJsonArray()) {
            com.google.gson.JsonArray list = ((JsonElement) attempts).getAsJsonArray();
            if (list.size() != 0) {
                copyFields(record, list.get(list.size() - 1));
            }
        }
    }

    private static void failureRecord(Path target, Object value, String serialized, String stage, Exception error) {
        JsonObject record = new JsonObject();
        record.addProperty("status", "EVIDENCE_SAVE_ERROR");
        record.addProperty("time_utc", Instant.now().toString());
        record.addProperty("target", target.toString());
        record.addProperty("stage", stage);
        record.addProperty("error_type", error.getClass().getName());
        record.addProperty("error", error.toString());
        originalFields(record, value);
        if (serialized != null) {
            Path recovery = target.resolveSibling(target.getFileName() + ".recovery-" + UUID.randomUUID() + ".json");
            try {
                writeAtomic(recovery, serialized);
                record.addProperty("recovery_file", recovery.getFileName().toString());
            } catch (IOException | RuntimeException recoveryError) {
                record.addProperty("recovery_error", recoveryError.toString());
                error.addSuppressed(recoveryError);
            }
        }
        try {
            writeAtomic(target.resolveSibling(target.getFileName() + ".save-error.json"), json(record));
        } catch (IOException | RuntimeException recordError) {
            error.addSuppressed(recordError);
            record.addProperty("error_record_error", recordError.toString());
            // A completely unwritable filesystem still leaves a Unicode-safe diagnostic in the process log.
            System.err.println(json(record));
        }
    }

    static void save(Path path, Object value) throws IOException {
        Path target = path.toAbsolutePath();
        String serialized = null;
        String stage = "serialize";
        try {
            serialized = json(value);
            stage = "write_or_replace";
            writeAtomic(target, serialized);
        } catch (IOException | RuntimeException error) {
            failureRecord(target, value, serialized, stage, error);
            throw error;
        }
    }
}
