package sqlancer.tidb.mixed;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Identity of the TiDB source revision against which this oracle is implemented. */
public final class TiDBMixedTarget {
    public static final String VERSION = "v9.0.0-beta.2.pre-1854-gb1d4142f99";
    public static final String COMMIT = "b1d4142f99a606aa3cd1ada6ee0caebaa84226dc";
    public static final String SQLANCER_BASE = "a513c87781ed879e5aae6bc78f8b6c6056bd7a99";

    private TiDBMixedTarget() {
    }

    public static void validate(String version) {
        // Local builds can override Release Version. The full source hash is authoritative.
        Matcher hash = Pattern.compile("(?m)^Git Commit Hash:\\s*([0-9a-fA-F]{10,40})\\s*$").matcher(version);
        if (!hash.find() || !matchesCommit(hash.group(1))) {
            throw new TiDBMixedSupport.Rejected("VERSION_MISMATCH",
                    "expected " + VERSION + " (" + COMMIT + "); got " + version);
        }
    }

    static boolean matchesCommit(String hash) {
        return hash != null && (hash.equalsIgnoreCase(COMMIT) || hash.equalsIgnoreCase(COMMIT.substring(0, 10)));
    }
}
