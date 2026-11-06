package dev.combatdata.core;

import java.util.UUID;

/**
 * Another player as the last packets showed them: the interpolation target, not the smoothed position drawn on
 * screen. Written only when something changed, so a missing sample means "same as before". The server sends player
 * movement every 2 ticks (at once on a jump); angles come in steps of 360/256 degrees.
 * item: main hand item id when it changed since the previous sample of this player, otherwise null;
 * EMPTY_HAND for an empty hand.
 */
public record OtherStateEvent(long t, int tick, UUID id, double x, double y, double z, float yaw, float pitch,
                              boolean onGround, boolean sprinting, String item) implements Event {
    public static final String EMPTY_HAND = "";

    @Override
    public Event withT(long newT) {
        return new OtherStateEvent(newT, tick, id, x, y, z, yaw, pitch, onGround, sprinting, item);
    }

    /** Same state, ignoring time and item. */
    public boolean sameState(OtherStateEvent o) {
        return o != null && id.equals(o.id) && x == o.x && y == o.y && z == o.z && yaw == o.yaw && pitch == o.pitch
                && onGround == o.onGround && sprinting == o.sprinting;
    }
}
