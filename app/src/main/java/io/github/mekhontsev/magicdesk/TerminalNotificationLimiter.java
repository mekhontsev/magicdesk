package io.github.mekhontsev.magicdesk;

/** No queue or timer: an OSC storm replaces session metadata but cannot flood Android. */
final class TerminalNotificationLimiter {
    private long last = Long.MIN_VALUE;

    boolean accept(final long now) {
        if (last != Long.MIN_VALUE && now - last < 2_000L) { return false; }
        last = now;
        return true;
    }
}
