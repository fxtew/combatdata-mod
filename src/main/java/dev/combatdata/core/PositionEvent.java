package dev.combatdata.core;

import java.util.List;
import java.util.UUID;

public record PositionEvent(long t, int tick, double x, double y, double z, float yaw, List<Other> others) implements Event {
    public record Other(UUID id, double x, double y, double z, float yaw) {
    }

    public static final double EYE = 1.62, HALF_W = 0.3, HEIGHT = 1.8;

    @Override
    public Event withT(long newT) {
        return new PositionEvent(newT, tick, x, y, z, yaw, others);
    }

    public Other find(UUID id) {
        for (Other o : others) if (o.id().equals(id)) return o;
        return null;
    }

    public double distanceTo(Other o) {
        Geometry.Aabb box = new Geometry.Aabb(o.x() - HALF_W, o.y(), o.z() - HALF_W, o.x() + HALF_W, o.y() + HEIGHT, o.z() + HALF_W);
        return box.nearestDistance(new Geometry.V3(x, y + EYE, z));
    }
}
