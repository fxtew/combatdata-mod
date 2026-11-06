package dev.combatdata.core;

import java.util.UUID;

/** An animation packet about another player. A swing is sent for every attack click, a miss too. */
public record OtherAnimEvent(long t, int tick, UUID id, Anim anim) implements Event {
    public enum Anim {
        SWING;

        public static Anim parse(String s) {
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
        return new OtherAnimEvent(newT, tick, id, anim);
    }
}
