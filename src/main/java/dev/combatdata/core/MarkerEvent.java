package dev.combatdata.core;

public record MarkerEvent(long t, String label) implements Event {
    @Override
    public Event withT(long newT) {
        return new MarkerEvent(newT, label);
    }
}
