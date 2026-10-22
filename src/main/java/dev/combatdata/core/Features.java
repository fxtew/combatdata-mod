package dev.combatdata.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Parts of a recording that can be turned off. The mask is stored in the .cdrec header ("notRecorded")
 * so the viewer can say "not recorded" instead of "no data".
 */
public final class Features {
    public static final int POSITIONS = 1;
    public static final int INPUT = 2;
    public static final int KNOCKBACK = 4;
    public static final int TAKEN = 8;
    public static final int[] ALL = {POSITIONS, INPUT, KNOCKBACK, TAKEN};
    private static final String[] KEYS = {"pos", "input", "kb", "taken"};
    /** Lite mode keeps incoming hits: they are needed to explain lost trades. */
    public static final int LITE = POSITIONS | INPUT | KNOCKBACK;

    private Features() {
    }

    public static List<String> names(int mask) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < ALL.length; i++) if ((mask & ALL[i]) != 0) out.add(KEYS[i]);
        return out;
    }

    /** Unknown keys (from newer versions) are ignored. */
    public static int parse(Object o) {
        int mask = 0;
        if (o instanceof List<?> l) {
            for (Object k : l) {
                for (int i = 0; i < KEYS.length; i++) if (KEYS[i].equals(k)) mask |= ALL[i];
            }
        }
        return mask;
    }
}
