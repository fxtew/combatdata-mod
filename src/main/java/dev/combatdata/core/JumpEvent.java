package dev.combatdata.core;

/** pressT: raw key press time, -1 if unknown. keys: movement keys at jump time as KEY_* bits, -1 if unknown. */
public record JumpEvent(long t, int tick, boolean onGround, long pressT, int keys) implements Event {
    public static final int KEY_FORWARD = 1, KEY_BACK = 2, KEY_LEFT = 4, KEY_RIGHT = 8, KEY_SPRINT = 16;
    public static final int KEYS_UNKNOWN = -1;

    public JumpEvent(long t, int tick, boolean onGround, long pressT) {
        this(t, tick, onGround, pressT, KEYS_UNKNOWN);
    }

    public static int keys(boolean forward, boolean back, boolean left, boolean right, boolean sprint) {
        return (forward ? KEY_FORWARD : 0) | (back ? KEY_BACK : 0) | (left ? KEY_LEFT : 0) | (right ? KEY_RIGHT : 0)
                | (sprint ? KEY_SPRINT : 0);
    }

    public boolean keysKnown() {
        return keys >= 0;
    }

    public boolean has(int key) {
        return keys >= 0 && (keys & key) != 0;
    }

    /** Fly out: jumping with S held and no W to get knocked further. The opposite of a jump reset. */
    public boolean flyOut() {
        return has(KEY_BACK) && !has(KEY_FORWARD);
    }

    @Override
    public Event withT(long newT) {
        return new JumpEvent(newT, tick, onGround, pressT < 0 ? pressT : pressT + (newT - t), keys);
    }
}
