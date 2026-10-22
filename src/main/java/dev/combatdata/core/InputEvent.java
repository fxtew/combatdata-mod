package dev.combatdata.core;

public record InputEvent(long t, int tick, Input what, boolean on) implements Event {
    public enum Input {
        FORWARD, BACK, SPRINT_KEY, SPRINTING;

        public static Input parse(String s) {
            if (s == null) return null;
            try {
                return valueOf(s);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    @Override
    public Event withT(long newT) {
        return new InputEvent(newT, tick, what, on);
    }
}
