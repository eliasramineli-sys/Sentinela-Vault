package br.com.sentinela;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Limitação de tentativas somente neste processo; não impede ataques offline. */
final class LoginThrottle {
    private final LongSupplier clock;
    private final Map<Path, Integer> failures = new HashMap<>();
    private final Map<Path, Long> deadlines = new HashMap<>();
    LoginThrottle(LongSupplier clock) { this.clock = clock; }
    long secondsLeft(Path path) {
        long left = deadlines.getOrDefault(path.toAbsolutePath().normalize(), clock.getAsLong()) - clock.getAsLong();
        return Math.max(0, (left + 999_999_999L) / 1_000_000_000L);
    }
    void failure(Path path) {
        Path key = path.toAbsolutePath().normalize();
        int count = failures.merge(key, 1, Integer::sum);
        if (count >= 5) {
            long seconds = Math.min(300, 30L << Math.min(count - 5, 4));
            deadlines.put(key, clock.getAsLong() + Duration.ofSeconds(seconds).toNanos());
        }
    }
    void success(Path path) {
        Path key = path.toAbsolutePath().normalize(); failures.remove(key); deadlines.remove(key);
    }
}
