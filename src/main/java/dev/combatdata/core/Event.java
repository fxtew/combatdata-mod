package dev.combatdata.core;

/** t() is milliseconds of active buffer time (the clock pauses). */
public sealed interface Event permits AttackEvent, IncomingHitEvent, JumpEvent, PingEvent, MarkerEvent, SegmentEvent,
        InputEvent, PositionEvent {
    long t();

    Event withT(long newT);
}
