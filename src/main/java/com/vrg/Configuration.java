package com.vrg;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.framework.qual.DefaultQualifier;
import org.checkerframework.framework.qual.TypeUseLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;


/**
 * A configuration represents a series of commitments to either add or
 * remove an identifier from the history. The identifier in this case
 * is a node.
 *
 * The configuration mandates that identifier additions are always unique.
 *
 * XXX: Not thread safe
 */
@DefaultQualifier(value = NonNull.class, locations = {TypeUseLocation.ALL})
public class Configuration {
    private final ArrayList<String> configHistory;
    private final ArrayList<List<Long>> opHistory;
    private final Set<Long> identifiersSeen = new HashSet<>(); // when to gc?
    private static final String ZERO = "zero"; // All histories start from zero.
    private static final String NONE = ""; // Indicates no common ancestor

    public Configuration() {
        this.configHistory = new ArrayList<>();
        this.opHistory = new ArrayList<>();
        this.configHistory.add(Utils.sha1HexStringToString(ZERO));
    }

    public Configuration(final ArrayList<String> configHistory,
                         final ArrayList<List<Long>> opHistory) {
        this.configHistory = configHistory;
        this.opHistory = opHistory;
        this.configHistory.add(Utils.sha1HexStringToString(ZERO));
    }

    public static ComparisonResult compare(final Configuration c1, final Configuration c2) {
        return compare(c1, c2.configHistory);
    }

    public static ComparisonResult compare(final Configuration c1, final List<String> c2) {
        assert c1 != null;
        assert c2 != null;

        // c1 either subsumes c2 or vice versa
        final int o1size = c1.configHistory.size();
        final int o2size = c2.size();
        assert o1size > 0;
        assert o2size > 0;
        final String c2Head = c2.get(o2size - 1);

        // First we compare the heads to see if they are compatible
        if (c1.head().equals(c2Head)) {
            return ComparisonResult.EQUAL;
        }

        final String divergingCommit = c1.findDivergingCommit(c2);
        // The next case, is either...

        // ... 1) There is no diverging commit, we need to go back further in history or sync
        if (divergingCommit.equals(NONE)) {
            return ComparisonResult.NO_COMMON_ANCESTOR; // do enums for return code checking
        }

        // ... 2) c2 is one or more configUpdates ahead of c1.head()  (c2 > c1).
        if (divergingCommit.equals(c1.head())) {
            return ComparisonResult.FAST_FORWARD_TO_RIGHT; // FF c1 to c2
        }
        // ... 3) c1 is one or more configUpdates ahead of c2.head()  (c1 > c2).
        if (divergingCommit.equals(c2Head)) {
            return ComparisonResult.FAST_FORWARD_TO_LEFT; // FF c2 to c1
        }
        // ... 4) Merge required, since c1 and c2 have both made some updates since the diverging point.
        // merge!
        return ComparisonResult.MERGE;
    }

    public void updateConfiguration(final List<Long> operations) {
        assert operations.size() > 0;
        // operation can either be add id, or remove id
        opHistory.add(operations);
        identifiersSeen.addAll(operations);
        configHistory.add(Utils.sha1HexStringToString(Utils.sha1HexLongsToString(identifiersSeen)));
    }

    private String findDivergingCommit(final Configuration c2) {
        return findDivergingCommit(c2.configHistory);
    }

    private String findDivergingCommit(final List<String> remoteConfigHistory) {
        final Set<String> localConfig = new HashSet<>(configHistory);

        for (int i = remoteConfigHistory.size() - 1; i >= 0; i--) {
            final String configId = remoteConfigHistory.get(i);
            if (localConfig.contains(configId)) {
                return configId;
            }
        }

        return NONE;
    }

    public String head() {
        final int size = configHistory.size();
        assert size > 0;
        return configHistory.get(size - 1);
    }

    enum ComparisonResult {
        EQUAL,
        FAST_FORWARD_TO_RIGHT,
        FAST_FORWARD_TO_LEFT,
        NO_COMMON_ANCESTOR,
        MERGE,
    }

    public String toString() {
        final StringBuilder sb = new StringBuilder();
        for (final String config: configHistory) {
            sb.append(config);
            sb.append(",");
        }
        return sb.toString();
    }
}