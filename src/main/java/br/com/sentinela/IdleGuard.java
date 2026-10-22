package br.com.sentinela;

import java.time.Duration;
import java.util.function.LongSupplier;

final class IdleGuard {
    static final long TIMEOUT = Duration.ofMinutes(2).toNanos();
    private final LongSupplier clock;
    private long touched;
    IdleGuard(LongSupplier clock) { this.clock = clock; touch(); }
    void touch() { touched = clock.getAsLong(); }
    boolean expired() { return clock.getAsLong() - touched >= TIMEOUT; }
    long secondsLeft() { return Math.max(0, (TIMEOUT - (clock.getAsLong() - touched)) / 1_000_000_000L); }
}
