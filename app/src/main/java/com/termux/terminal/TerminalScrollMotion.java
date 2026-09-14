package com.termux.terminal;

/** A monotonic, velocity-continuous follower of already committed scroll distance. */
final class TerminalScrollMotion {
    private double remaining, velocity;
    private double frameMillis, responseMillis, rate;
    private long updatedAt;
    private long lastInput = -1;
    private int inputDirection;
    private boolean cadenceKnown;

    void reset() {
        remaining = velocity = 0;
        updatedAt = 0;
        lastInput = -1;
        inputDirection = 0;
        responseMillis = 0;
        cadenceKnown = false;
    }

    void input(int direction, boolean continuous, double frame, long now) {
        advance(now);
        // Bootstrap a gesture from its second command, even if the first already settled.
        // Once paced, reaching the target identifies a pause, not a slower input cadence.
        boolean continuing = direction == inputDirection && lastInput >= 0
                && (remaining != 0 || continuous && !cadenceKnown);
        frameMillis = frame;
        cadenceKnown = continuing && now > lastInput;
        responseMillis = cadenceKnown
                ? Math.max(frame, (now - lastInput) / 2.0) : frame;
        inputDirection = direction;
        lastInput = now;
        retune();
    }

    void endInput(long now) {
        advance(now);
        lastInput = -1;
        cadenceKnown = false;
        responseMillis = frameMillis;
        retune();
    }

    void add(double pixels, double maximum, double frame, long now) {
        advance(now);
        frameMillis = frame;
        responseMillis = Math.max(frame, responseMillis);
        remaining = Math.min(maximum, remaining + pixels);
        updatedAt = Math.max(updatedAt, now);
        retune();
    }

    private void retune() {
        if (responseMillis <= 0) return;
        // A slower cadence must not let existing velocity overshoot the known target.
        rate = Math.max(1 / responseMillis, remaining > 0 ? velocity / remaining : 0);
    }

    boolean advance(long now) {
        if (remaining == 0) return false;
        double elapsed = Math.max(0, now - updatedAt);
        updatedAt = Math.max(updatedAt, now);
        // Exact critically damped motion, not Euler steps tied to the frame rate.
        // Adding a target preserves velocity; without input it stops without overshoot.
        double coefficient = rate * remaining - velocity;
        double decay = Math.exp(-rate * elapsed);
        remaining = (remaining + coefficient * elapsed) * decay;
        velocity = (velocity + rate * coefficient * elapsed) * decay;
        if (remaining < 0.5) remaining = velocity = 0;
        return remaining != 0;
    }

    double remainingPixels() { return remaining; }

    double pixelsPerMillis() { return velocity; }
}
