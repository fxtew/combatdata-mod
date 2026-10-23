package dev.combatdata.core;

/** Note block sounds: note 0..24 is F#3..F#5, pitch 2^((note - 12) / 12) as in vanilla. */
public final class Notes {
    public static final String[] INSTRUMENTS = {"pling", "bell", "chime", "xylophone", "iron_xylophone", "harp", "bit",
            "flute", "guitar", "banjo", "cow_bell", "didgeridoo", "bass", "basedrum", "snare", "hat"};
    public static final int MAX_NOTE = 24;
    private static final String[] NAMES = {"F#", "G", "G#", "A", "A#", "B", "C", "C#", "D", "D#", "E", "F"};

    private Notes() {
    }

    public static int clampNote(int note) {
        return Math.max(0, Math.min(MAX_NOTE, note));
    }

    public static String name(int note) {
        int n = clampNote(note);
        return NAMES[n % 12] + (3 + (n + 6) / 12);
    }

    public static float pitch(int note) {
        return (float) Math.pow(2, (clampNote(note) - 12) / 12.0);
    }

    public static boolean known(String id) {
        return index(id) >= 0;
    }

    public static int index(String id) {
        for (int i = 0; i < INSTRUMENTS.length; i++) if (INSTRUMENTS[i].equals(id)) return i;
        return -1;
    }

    /** Next or previous instrument in the list, wrapping around. */
    public static String step(String id, int dir) {
        int i = Math.max(0, index(id));
        return INSTRUMENTS[Math.floorMod(i + dir, INSTRUMENTS.length)];
    }
}
