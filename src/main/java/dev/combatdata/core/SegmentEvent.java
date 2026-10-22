package dev.combatdata.core;

public record SegmentEvent(long t, String server, String dimension, long wallStartMs) implements Event {
    @Override
    public Event withT(long newT) {
        return new SegmentEvent(newT, server, dimension, wallStartMs);
    }
}
