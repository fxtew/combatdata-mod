package dev.combatdata.core;

public record PingEvent(long t, int pingMs) implements Event {
    @Override
    public Event withT(long newT) {
        return new PingEvent(newT, pingMs);
    }
}
