package dev.combatdata.core;

import java.util.UUID;

/** The mod author's account; their name is drawn in gold. */
public final class Author {
    public static final UUID ID = UUID.fromString("1d33ebee-33c0-470e-9b83-47292866c8ab");

    private Author() {
    }

    public static boolean is(UUID id) {
        return ID.equals(id);
    }
}
