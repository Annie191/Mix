package sqlancer.tidb.mixed;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import sqlancer.Randomly;
import sqlancer.SQLConnection;
import sqlancer.tidb.TiDBProvider.TiDBGlobalState;
import sqlancer.tidb.TiDBSchema.TiDBTable;
import sqlancer.tidb.mixed.TiDBMixedGeneration.Candidate;

public final class TiDBMixedSupport {
    private final TiDBGlobalState state;
    public final List<Map<String, Object>> events = new ArrayList<>();
    public final Map<String, Object> environment = new LinkedHashMap<>();
    public String snapshot = "";

    public static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String status;

        public Rejected(String status, String message) {
            super(message);
            this.status = status;
        }
    }

    public static final class Config {
        public final Map<String, String> engines;
        public final String name;

        public Config(Map<String, String> engines) {
            if (engines.isEmpty()) {
                throw new IllegalArgumentException("configuration needs at least one table");
            }
            engines.forEach((table, engine) -> {
                quoteIdentifier(table);
                if (!Set.of("tikv", "tiflash").contains(engine)) {
                    throw new IllegalArgumentException("unknown storage engine: " + engine);
                }
            });
            this.engines = Collections.unmodifiableMap(new LinkedHashMap<>(engines));
            this.name = engines.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(","));
        }

        public boolean mixed() {
            return new LinkedHashSet<>(engines.values()).size() > 1;
        }
    }

    public static final class Run {
        public final Config config;
        public final String sql;
        public String status = "PENDING";
        public List<Map<String, String>> plan = new ArrayList<>();
        public List<Map<String, String>> analyzed = new ArrayList<>();
        public Map<List<String>, Long> bag = new HashMap<>();
        public List<List<String>> rows = new ArrayList<>();
        public List<String> columns = new ArrayList<>();
        public String error = "";
        public String evidence = "PLAN_ONLY; ANALYZE is a separate execution";
        public String snapshot;

        Run(Config config, String sql) {
            this.config = config;
            this.sql = sql;
        }
    }

    public static final class Outcome {
        public String status;
        public boolean allPathsValid;
        public final List<Map<String, Object>> comparisons = new ArrayList<>();
        public final List<String> failures = new ArrayList<>();
        public final Set<String> differingPairs = new TreeSet<>();
        public final Set<String> mismatchTypes = new TreeSet<>();

        public boolean mismatch() {
            return !differingPairs.isEmpty();
        }

        public String signature() {
            return status + differingPairs.toString();
        }
    }

    public TiDBMixedSupport(TiDBGlobalState state) {
        this.state = state;
    }

    SQLConnection connection() {
        return state.getConnection();
    }

    boolean integerColumn(String table, String column) {
        if (state.getSchema() == null) {
            return false;
        }
        return state.getSchema().getDatabaseTables().stream().filter(t -> t.getName().equalsIgnoreCase(table))
                .flatMap(t -> t.getColumns().stream()).anyMatch(c -> c.getName().equalsIgnoreCase(column)
                        && TiDBMixedMetadata.valueKind(c.getSqlType()).equals("integer"));
    }

    public static String quoteIdentifier(String identifier) {
        if (!identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("identifier outside generated grammar");
        }
        return "`" + identifier + "`";
    }

    public static String literal(String value) {
        return "CONVERT(X'" + hex(value.getBytes(StandardCharsets.UTF_8)) + "' USING utf8mb4)";
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte value : bytes) {
            out.append(String.format("%02x", value & 255));
        }
        return out.toString();
    }

    public void exec(String sql) throws SQLException {
        state.getState().logStatement(sql);
        events.add(Map.of("stage", "SQL", "sql", sql));
        try (Statement statement = state.getConnection().createStatement()) {
            statement.setQueryTimeout(35);
            statement.execute(sql);
            warnings(statement, sql);
        }
    }

    private void warnings(Statement statement, String sql) throws SQLException {
        List<String> warnings = new ArrayList<>();
        List<String> notices = new ArrayList<>();
        for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
            String text = warning.getErrorCode() + ":" + warning.getMessage();
            if (planningNotice(sql, warning)) {
                notices.add(text);
            } else {
                warnings.add(text);
            }
        }
        if (!notices.isEmpty()) {
            events.add(Map.of("stage", "PLAN_CAPABILITY_NOTICE", "sql", sql, "warnings", notices,
                    "requires_scan_validation", true));
        }
        if (!warnings.isEmpty()) {
            events.add(Map.of("stage", "WARNING", "sql", sql, "warnings", warnings));
            throw new Rejected("INCONCLUSIVE", "SQL warnings require review: " + sql + "; " + warnings);
        }
    }

    static boolean planningNotice(String sql, SQLWarning warning) {
        // Only optimizer capability messages on EXPLAIN; execution warnings and ignored hints stay fatal.
        if (!sql.startsWith("EXPLAIN ") || warning.getErrorCode() != 1105) {
            return false;
        }
        String message = String.valueOf(warning.getMessage());
        return message.matches("Aggregation can not be pushed to tiflash because AggFunc `[^`]+` is not supported now")
                || message.matches(
                        "Aggregation can not be pushed to tiflash because arguments of AggFunc `[^`]+` contains unsupported exprs")
                || message.matches(
                        "Scalar function '[^']+'\\(signature: [^,]+, return type: [^)]+\\) is not supported to push down to tiflash now\\.");
    }

    static void requireScannedCandidate(List<Map<String, String>> plan) {
        boolean scan = plan.stream().anyMatch(row -> row.getOrDefault("access object", "").contains("table:"));
        if (!scan) {
            throw new Rejected("NO_STORAGE_SCAN",
                    "candidate plan does not read a base table; no engine path to compare");
        }
    }

    void inspectCandidate(String sql) throws SQLException {
        // MixedGeneration emits SELECT without LIMIT/OFFSET. LIMIT 0 asks for the same projection metadata only.
        String metadataSql = sql + " LIMIT 0";
        events.add(Map.of("stage", "CANDIDATE_METADATA", "sql", metadataSql));
        try (Statement statement = connection().createStatement()) {
            statement.setQueryTimeout(35);
            try (ResultSet rows = statement.executeQuery(metadataSql)) {
                ResultSetMetaData metadata = rows.getMetaData();
                for (int i = 1; i <= metadata.getColumnCount(); i++) {
                    int type = metadata.getColumnType(i);
                    if (Set.of(Types.FLOAT, Types.REAL, Types.DOUBLE).contains(type)) {
                        throw new Rejected("UNSUPPORTED_EXACT_RESULT_TYPE", "column " + i + ": "
                                + metadata.getColumnTypeName(i) + " is outside the exact differential oracle");
                    }
                }
            }
            warnings(statement, metadataSql);
        }
    }

    void preparationRead(Candidate query) throws SQLException {
        exec("SET SESSION tidb_isolation_read_engines='tikv'");
        try {
            // Execute without buffering data rows. Streaming SELECT can discard warnings when the stream closes.
            List<Map<String, String>> analyzed = textRows("EXPLAIN ANALYZE " + query.sql);
            List<Map<String, String>> serverWarnings = textRows("SHOW WARNINGS");
            if (!serverWarnings.isEmpty()) {
                events.add(Map.of("stage", "PREPARATION_WARNING", "sql", query.sql, "warnings", serverWarnings));
                throw new Rejected("INCONCLUSIVE", "Preparation read produced server warnings: " + serverWarnings);
            }
            events.add(Map.of("stage", "PREPARATION_READ", "status", "OK", "analyzed", analyzed));
        } finally {
            exec("SET SESSION tidb_isolation_read_engines='tikv,tiflash'");
        }
    }

    public List<Map<String, String>> textRows(String sql) throws SQLException {
        events.add(Map.of("stage", "READ", "sql", sql));
        try (Statement s = state.getConnection().createStatement()) {
            s.setQueryTimeout(35);
            List<Map<String, String>> rows = new ArrayList<>();
            try (ResultSet rs = s.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        row.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getString(i));
                    }
                    rows.add(row);
                }
            }
            warnings(s, sql);
            return rows;
        }
    }

    public void initialize() throws SQLException {
        String version = textRows("SELECT TIDB_VERSION() AS version").get(0).get("version");
        environment.put("tidbVersion", version);
        TiDBMixedTarget.validate(version);
        List<Map<String, String>> nodes = textRows(
                "SELECT TYPE, VERSION, GIT_HASH FROM information_schema.cluster_info");
        environment.put("components", nodes);
        if (nodes.stream().filter(r -> "tidb".equals(r.get("type")))
                .anyMatch(r -> !TiDBMixedTarget.matchesCommit(r.get("git_hash")))) {
            throw new Rejected("VERSION_MISMATCH", "all TiDB nodes must use " + TiDBMixedTarget.COMMIT);
        }
        Set<String> types = nodes.stream().map(r -> r.get("type")).collect(Collectors.toSet());
        if (!types.containsAll(Set.of("tidb", "tikv", "tiflash", "pd"))) {
            throw new Rejected("UNSUPPORTED", "TiDB/TiKV/TiFlash/PD must all be present");
        }
        exec("SET SESSION time_zone='+00:00'");
        exec("SET NAMES utf8mb4 COLLATE utf8mb4_bin");
        exec("SET SESSION sql_mode='STRICT_TRANS_TABLES,ONLY_FULL_GROUP_BY,NO_ENGINE_SUBSTITUTION'");
        exec("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");
        exec("SET SESSION tidb_isolation_read_engines='tikv,tiflash'");
        exec("SET SESSION tidb_allow_mpp=1");
        exec("SET SESSION tidb_enforce_mpp=0");
        exec("SET SESSION tiflash_fastscan=0");
        exec("SET SESSION max_execution_time=30000");
        environment.put("session", textRows("SHOW SESSION VARIABLES"));
    }

    public void replicas(List<TiDBTable> tables) throws Exception {
        for (TiDBTable table : tables) {
            // The default auto sample rate emits a Note (1105) on this TiDB revision.
            // Full sampling is bounded by the small generated dataset and needs no warning exemption.
            exec("ANALYZE TABLE " + quoteIdentifier(table.getName()) + " WITH 1 SAMPLERATE");
            exec("ALTER TABLE " + quoteIdentifier(table.getName()) + " SET TIFLASH REPLICA 1");
        }
        long deadline = System.nanoTime() + 180_000_000_000L;
        while (System.nanoTime() < deadline) {
            List<Map<String, String>> replicas = textRows("SELECT TABLE_NAME, AVAILABLE, PROGRESS FROM "
                    + "information_schema.tiflash_replica WHERE TABLE_SCHEMA=" + literal(state.getDatabaseName()));
            environment.put("replicas", replicas);
            Set<String> available = replicas.stream().filter(r -> "1".equals(r.get("available")))
                    .map(r -> r.get("table_name")).collect(Collectors.toSet());
            if (available.containsAll(tables.stream().map(TiDBTable::getName).collect(Collectors.toList()))) {
                return;
            }
            Thread.sleep(1000);
        }
        throw new Rejected("REPLICA_NOT_READY", "availability wait exceeded 180 seconds");
    }

    static boolean positiveTso(String value) {
        return value != null && value.matches("[1-9][0-9]{0,19}") && new BigInteger(value).bitLength() <= 64;
    }

    private Map<String, String> snapshotSession() throws SQLException {
        List<Map<String, String>> rows = textRows("SELECT @@tidb_snapshot AS snapshot,"
                + " @@tidb_current_ts AS transaction_ts, @@autocommit AS autocommit");
        if (rows.size() != 1) {
            throw new Rejected("SNAPSHOT_ERROR", "expected one snapshot session row");
        }
        return rows.get(0);
    }

    private static Rejected snapshotFailure(Exception cause) {
        Rejected failure = new Rejected("SNAPSHOT_ERROR", cause.toString());
        failure.initCause(cause);
        return failure;
    }

    void verifySnapshot(String expected) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("stage", "SNAPSHOT_CHECK");
        evidence.put("expected", expected);
        evidence.put("status", "SNAPSHOT_ERROR");
        events.add(evidence);
        try {
            if (!positiveTso(expected)) {
                throw new Rejected("SNAPSHOT_ERROR", "snapshot must be a positive uint64 TSO");
            }
            Map<String, String> session = snapshotSession();
            evidence.put("session", session);
            if (!expected.equals(session.get("snapshot")) || !"0".equals(session.get("transaction_ts"))
                    || !"1".equals(session.get("autocommit"))) {
                throw new Rejected("SNAPSHOT_ERROR", "snapshot readback or transaction context differs");
            }
            evidence.put("status", "VERIFIED");
        } catch (SQLException | RuntimeException error) {
            evidence.put("error", error.toString());
            throw snapshotFailure(error);
        }
    }

    public void beginSnapshot() throws SQLException {
        snapshot = "";
        environment.remove("snapshotTSO");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("stage", "SNAPSHOT_ACQUIRE");
        evidence.put("status", "SNAPSHOT_ERROR");
        events.add(evidence);
        boolean transactionStarted = false;
        boolean settingAttempted = false;
        try {
            Map<String, String> session = snapshotSession();
            if (!"0".equals(session.get("transaction_ts")) || !"1".equals(session.get("autocommit"))
                    || !Set.of("", "0").contains(String.valueOf(session.get("snapshot")))) {
                throw new Rejected("SNAPSHOT_ERROR", "acquisition requires an idle autocommit session");
            }
            transactionStarted = true; // BEGIN can succeed on the server even if acknowledgement fails.
            exec("BEGIN");
            List<Map<String, String>> rows = textRows("SELECT TIDB_CURRENT_TSO() AS tso");
            String acquired = rows.size() == 1 ? rows.get(0).get("tso") : null;
            evidence.put("requested", acquired);
            if (!positiveTso(acquired)) {
                throw new Rejected("SNAPSHOT_ERROR", "transaction returned no positive uint64 TSO");
            }
            exec("ROLLBACK"); // End the read-only acquisition transaction before setting historical reads.
            transactionStarted = false;
            settingAttempted = true;
            exec("SET SESSION tidb_snapshot='" + acquired + "'");
            verifySnapshot(acquired);
            snapshot = acquired;
            environment.put("snapshotTSO", acquired);
            evidence.put("status", "VERIFIED");
        } catch (SQLException | RuntimeException error) {
            evidence.put("error", error.toString());
            if (transactionStarted) {
                try {
                    exec("ROLLBACK");
                } catch (SQLException | RuntimeException cleanup) {
                    error.addSuppressed(cleanup);
                    evidence.put("rollbackError", cleanup.toString());
                }
            }
            if (settingAttempted) {
                try {
                    exec("SET SESSION tidb_snapshot=''");
                } catch (SQLException | RuntimeException cleanup) {
                    error.addSuppressed(cleanup);
                    evidence.put("clearError", cleanup.toString());
                }
            }
            throw snapshotFailure(error);
        }
    }

    public void reset() throws SQLException {
        exec("SET SESSION tidb_snapshot=''");
        snapshot = "";
        exec("SET SESSION tidb_isolation_read_engines='tikv,tiflash'");
    }

    public static List<Config> enumerateConfigurations(List<String> tables) {
        if (tables.size() < 2 || new LinkedHashSet<>(tables).size() != tables.size()) {
            throw new IllegalArgumentException("mixed assignments require distinct tables, at least two");
        }
        List<Config> result = new ArrayList<>();
        result.add(new Config(Map.of(tables.get(0), "tikv")));
        result.add(new Config(Map.of(tables.get(0), "tiflash")));
        for (int i = 1; i < tables.size(); i++) {
            List<Config> expanded = new ArrayList<>();
            for (Config prefix : result) {
                for (String engine : List.of("tikv", "tiflash")) {
                    Map<String, String> assignments = new LinkedHashMap<>(prefix.engines);
                    assignments.put(tables.get(i), engine);
                    expanded.add(new Config(assignments));
                }
            }
            result = expanded;
        }
        return result;
    }

    public static List<Config> configurations(List<String> tables) {
        List<Config> all = enumerateConfigurations(tables);
        List<Config> selected = all.stream().filter(c -> !c.mixed()).collect(Collectors.toList());
        List<Config> mixed = all.stream().filter(Config::mixed).collect(Collectors.toList());
        int count = requiredConfigurationCount(tables.size());
        while (selected.size() < count) {
            selected.add(mixed.remove((int) Randomly.getNotCachedInteger(0, mixed.size())));
        }
        return selected;
    }

    private static int requiredConfigurationCount(int tableCount) {
        return tableCount == 2 ? 4 : tableCount == 3 ? 8 : 6;
    }

    public static String configure(String base, Config config) {
        if (!base.startsWith("SELECT ") || base.startsWith("SELECT /*")) {
            throw new IllegalArgumentException("expected frozen, hint-free SELECT");
        }
        List<String> parts = new ArrayList<>();
        for (String engine : List.of("tikv", "tiflash")) {
            String tables = config.engines.entrySet().stream().filter(e -> e.getValue().equals(engine))
                    .map(e -> quoteIdentifier(e.getKey())).collect(Collectors.joining(","));
            if (!tables.isEmpty()) {
                parts.add(engine.toUpperCase(Locale.ROOT) + "[" + tables + "]");
            }
        }
        return "SELECT /*+ READ_FROM_STORAGE(" + String.join(",", parts) + ") */ " + base.substring(7);
    }

    public static void validatePlan(List<Map<String, String>> plan, Config config) {
        Map<String, Set<String>> actual = new LinkedHashMap<>();
        for (Map<String, String> row : plan) {
            String operator = row.getOrDefault("id", "");
            if (!(operator.contains("Scan") || operator.contains("Point_Get") || operator.contains("PointGet"))) {
                continue;
            }
            Matcher table = Pattern.compile("table:([A-Za-z0-9_`]+)").matcher(row.getOrDefault("access object", ""));
            Matcher engine = Pattern.compile("\\[(tikv|tiflash)\\]").matcher(row.getOrDefault("task", ""));
            if (!table.find() || !engine.find()) {
                throw new Rejected("INVALID_CONFIGURATION", "unknown access format: " + row);
            }
            actual.computeIfAbsent(table.group(1).replace("`", ""), t -> new LinkedHashSet<>()).add(engine.group(1));
        }
        Map<String, Set<String>> expected = new LinkedHashMap<>();
        config.engines.forEach((table, engine) -> expected.put(table, Set.of(engine)));
        if (!actual.equals(expected)) {
            throw new Rejected("INVALID_CONFIGURATION", "expected " + expected + "; observed " + actual);
        }
    }

    public static String numeric(String value) {
        return "N:" + new BigDecimal(value).stripTrailingZeros().toPlainString();
    }

    static String cell(ResultSet rs, int i, int type, boolean allowFloat) throws SQLException {
        if (rs.getObject(i) == null) {
            return "NULL";
        }
        switch (type) {
        case Types.TINYINT:
        case Types.SMALLINT:
        case Types.INTEGER:
        case Types.BIGINT:
        case Types.DECIMAL:
        case Types.NUMERIC:
            return numeric(rs.getString(i));
        case Types.BOOLEAN:
        case Types.BIT:
            return "B:" + Base64.getEncoder().encodeToString(rs.getBytes(i));
        case Types.FLOAT:
        case Types.REAL:
        case Types.DOUBLE:
            if (!allowFloat) {
                throw new Rejected("INCONCLUSIVE", "floating-point result outside exact oracle");
            }
            return "F:" + Double.toHexString(rs.getDouble(i));
        case Types.BINARY:
        case Types.VARBINARY:
        case Types.LONGVARBINARY:
        case Types.BLOB:
            return "B:" + Base64.getEncoder().encodeToString(rs.getBytes(i));
        case Types.CHAR:
        case Types.VARCHAR:
        case Types.LONGVARCHAR:
        case Types.DATE:
        case Types.TIME:
        case Types.TIMESTAMP:
            return "S:" + rs.getString(i);
        default:
            throw new Rejected("INCONCLUSIVE", "unsupported JDBC result type " + type);
        }
    }

    private void result(Run run, boolean allowFloat) throws SQLException {
        try (Statement s = state.getConnection().createStatement()) {
            s.setQueryTimeout(35);
            // Connector/J streams rows instead of buffering an unbounded result before the limit is checked.
            s.setFetchSize(Integer.MIN_VALUE);
            try (ResultSet rs = s.executeQuery(run.sql)) {
                ResultSetMetaData md = rs.getMetaData();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    run.columns.add(md.getColumnLabel(i) + ":" + md.getColumnTypeName(i));
                    if (!allowFloat && Set.of(Types.FLOAT, Types.REAL, Types.DOUBLE).contains(md.getColumnType(i))) {
                        throw new Rejected("INCONCLUSIVE", "floating-point result outside exact oracle");
                    }
                }
                long bytes = 0;
                while (rs.next()) {
                    if (run.rows.size() >= 10000) {
                        throw new Rejected("INCONCLUSIVE", "result row budget exceeded");
                    }
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String value = cell(rs, i, md.getColumnType(i), allowFloat);
                        bytes += (long) value.length() * Character.BYTES;
                        if (bytes > 16 * 1024 * 1024) {
                            throw new Rejected("INCONCLUSIVE", "result byte budget exceeded");
                        }
                        row.add(value);
                    }
                    run.rows.add(row);
                    run.bag.merge(row, 1L, Long::sum);
                }
            }
            warnings(s, run.sql);
        }
    }

    public Run execute(String base, Config config, boolean allowFloat) {
        Run run = new Run(config, configure(base, config));
        run.snapshot = snapshot;
        try {
            exec("SET SESSION tidb_isolation_read_engines='"
                    + (config.mixed() ? "tikv,tiflash" : config.engines.values().iterator().next()) + "'");
            verifySnapshot(run.snapshot);
            run.plan = textRows("EXPLAIN FORMAT='brief' " + run.sql);
            validatePlan(run.plan, config);
            verifySnapshot(run.snapshot);
            run.analyzed = textRows("EXPLAIN ANALYZE " + run.sql);
            validatePlan(run.analyzed, config);
            verifySnapshot(run.snapshot);
            result(run, allowFloat);
            verifySnapshot(run.snapshot);
            run.status = "OK";
        } catch (Rejected e) {
            run.status = e.status;
            run.error = e.getMessage();
        } catch (SQLException e) {
            run.status = "EXECUTION_ERROR";
            run.error = e.getSQLState() + ":" + e.getErrorCode() + ":" + e.getMessage();
        }
        return run;
    }

    public List<Run> executeAll(String sql, List<Config> configs) {
        List<Config> order = new ArrayList<>(configs);
        List<Run> result = new ArrayList<>();
        while (!order.isEmpty()) {
            result.add(execute(sql, order.remove((int) Randomly.getNotCachedInteger(0, order.size())), false));
        }
        return result;
    }

    public static Outcome compare(List<Run> runs) {
        Outcome verdict = new Outcome();
        for (Run run : runs) {
            if (!run.status.equals("OK")) {
                verdict.failures.add(run.config.name + ":" + run.status);
            }
        }
        Set<String> successfulSnapshots = runs.stream().filter(r -> r.status.equals("OK")).map(r -> r.snapshot)
                .collect(Collectors.toSet());
        if (successfulSnapshots.stream().anyMatch(tso -> !positiveTso(tso)) || successfulSnapshots.size() > 1) {
            verdict.failures.add("SNAPSHOT_ERROR: successful paths do not share one positive TSO");
            verdict.status = "SNAPSHOT_ERROR";
            return verdict;
        }
        verdict.allPathsValid = completeConfigurations(runs) && verdict.failures.isEmpty();
        for (int i = 0; i < runs.size(); i++) {
            for (int j = i + 1; j < runs.size(); j++) {
                Run a = runs.get(i);
                Run b = runs.get(j);
                if (!a.status.equals("OK") || !b.status.equals("OK")) {
                    continue;
                }
                boolean equal = a.columns.equals(b.columns) && a.bag.equals(b.bag);
                boolean baselinePair = !a.config.mixed() && !b.config.mixed();
                boolean mixedPair = a.config.mixed() && b.config.mixed();
                String mismatchType = baselinePair ? "BASELINE_DISAGREEMENT"
                        : mixedPair ? "MIXED_VS_MIXED_MISMATCH" : "MIXED_VS_SINGLE_MISMATCH";
                verdict.comparisons.add(Map.of("left", a.config.name, "right", b.config.name, "equal", equal,
                        "baselinePair", baselinePair, "mismatchType", mismatchType));
                if (!equal) {
                    verdict.differingPairs.add(new TreeSet<>(List.of(a.config.name, b.config.name)).toString());
                    verdict.mismatchTypes.add(mismatchType);
                }
            }
        }
        if (verdict.mismatchTypes.contains("BASELINE_DISAGREEMENT")) {
            verdict.status = "BASELINE_DISAGREEMENT";
        } else if (verdict.mismatchTypes.contains("MIXED_VS_SINGLE_MISMATCH")) {
            verdict.status = "MIXED_VS_SINGLE_MISMATCH";
        } else if (verdict.mismatchTypes.contains("MIXED_VS_MIXED_MISMATCH")) {
            verdict.status = "MIXED_VS_MIXED_MISMATCH";
        } else if (runs.stream().anyMatch(r -> r.status.equals("SNAPSHOT_ERROR"))) {
            verdict.status = "SNAPSHOT_ERROR";
        } else if (runs.stream().anyMatch(r -> r.status.equals("EXECUTION_ERROR"))) {
            verdict.status = "EXECUTION_ERROR";
        } else if (runs.stream().anyMatch(r -> r.status.equals("INCONCLUSIVE"))) {
            verdict.status = "INCONCLUSIVE";
        } else if (!verdict.allPathsValid) {
            verdict.status = "INVALID_CONFIGURATION";
        } else {
            verdict.status = "PASS";
        }
        return verdict;
    }

    private static boolean completeConfigurations(List<Run> runs) {
        if (runs.isEmpty()) {
            return false;
        }
        Set<String> tables = runs.get(0).config.engines.keySet();
        if (tables.size() < 2 || runs.stream().anyMatch(r -> !r.config.engines.keySet().equals(tables))) {
            return false;
        }
        int count = requiredConfigurationCount(tables.size());
        if (runs.size() != count || runs.stream().filter(r -> r.config.mixed()).count() != count - 2
                || runs.stream().map(r -> r.config.name).distinct().count() != count) {
            return false;
        }
        Set<String> baselines = runs.stream().filter(r -> !r.config.mixed())
                .map(r -> r.config.engines.values().iterator().next()).collect(Collectors.toSet());
        return baselines.equals(Set.of("tikv", "tiflash"));
    }

    public void preflight(List<TiDBTable> tables) {
        for (TiDBTable table : tables) {
            String name = table.getName();
            String sql = "SELECT * FROM " + quoteIdentifier(name);
            Run kv = execute(sql, new Config(Map.of(name, "tikv")), true);
            Run flash = execute(sql, new Config(Map.of(name, "tiflash")), true);
            events.add(Map.of("stage", "PREFLIGHT", "tikv", kv, "tiflash", flash));
            if (kv.status.equals("SNAPSHOT_ERROR") || flash.status.equals("SNAPSHOT_ERROR")) {
                throw new Rejected("SNAPSHOT_ERROR", kv.error + ";" + flash.error);
            }
            if (!kv.status.equals("OK") || !flash.status.equals("OK")) {
                throw new Rejected("PREFLIGHT_FAILED", kv.status + ";" + flash.status);
            }
            if (!kv.columns.equals(flash.columns) || !kv.bag.equals(flash.bag)) {
                throw new Rejected("DATA_PREFLIGHT_MISMATCH", "base-table engine disagreement");
            }
        }
    }

    public List<String> dump(List<TiDBTable> tables) throws SQLException {
        List<String> statements = new ArrayList<>();
        exec("SET SESSION tidb_isolation_read_engines='tikv'");
        for (TiDBTable table : tables) {
            String name = quoteIdentifier(table.getName());
            statements.add(textRows("SHOW CREATE TABLE " + name).get(0).get("create table"));
            List<String> writable = textRows("SHOW FULL COLUMNS FROM " + name).stream()
                    .filter(r -> !String.valueOf(r.get("extra")).contains("GENERATED"))
                    .map(r -> quoteIdentifier(r.get("field"))).collect(Collectors.toList());
            if (writable.isEmpty()) {
                throw new Rejected("DUMP_UNSUPPORTED", "no writable columns");
            }
            try (Statement s = state.getConnection().createStatement();
                    ResultSet rs = s.executeQuery("SELECT " + String.join(",", writable) + " FROM " + name)) {
                while (rs.next()) {
                    List<String> values = new ArrayList<>();
                    for (int i = 1; i <= writable.size(); i++) {
                        Object value = rs.getObject(i);
                        if (value == null) {
                            values.add("NULL");
                        } else if (value instanceof byte[]) {
                            values.add("X'" + hex((byte[]) value) + "'");
                        } else if (value instanceof Number) {
                            values.add(value.toString());
                        } else if (value instanceof Boolean) {
                            values.add(Boolean.TRUE.equals(value) ? "1" : "0");
                        } else {
                            values.add(literal(rs.getString(i)));
                        }
                    }
                    statements.add("INSERT INTO " + name + " (" + String.join(",", writable) + ") VALUES ("
                            + String.join(",", values) + ")");
                }
            }
        }
        return statements;
    }

    void auxiliaryFailure(TiDBMixedGeneration.Candidate candidate, Map<String, Object> context, SQLException error) {
        if (state.getAuxiliaryErrors() != null) {
            state.getAuxiliaryErrors().record(this, candidate, context, error);
        }
    }

    public static void save(Path path, Object value) throws java.io.IOException {
        TiDBMixedEvidence.save(path, value);
    }
}
