package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** One temporary system theme shared by all active Desktop workspaces. */
final class DesktopSystemThemeSession {
    enum Preference {
        UNCHANGED, LIGHT, DARK;

        static Preference parse(final String value) {
            try { return valueOf(value); }
            catch (IllegalArgumentException | NullPointerException ignored) { return UNCHANGED; }
        }

        SystemNightMode nightMode() { return SystemNightMode.valueOf(name()); }
    }

    record Override(SystemNightMode previous, SystemNightMode applied) { }

    interface Access {
        SystemNightMode read() throws IOException;
        void write(SystemNightMode mode) throws IOException;
    }

    interface Storage {
        Override read() throws IOException;
        void write(Override value) throws IOException;
    }

    private final Access mAccess;
    private final Storage mStorage;
    private Preference mRequested;
    private boolean mActive;

    DesktopSystemThemeSession(final Access access, final Storage storage) {
        mAccess = access;
        mStorage = storage;
    }

    /** Returns false when the same active request was already attempted. */
    boolean update(final Preference preference, final boolean active) throws IOException {
        if (!active || preference == Preference.UNCHANGED) {
            mActive = active;
            mRequested = preference;
            restore();
            return true;
        }
        if (mActive && preference == mRequested) return false;
        // A setting or session change is an explicit request. Ordinary workspace
        // refreshes must not reassert it after a user's system-theme change.
        mActive = active;
        mRequested = preference;
        final Override pending = mStorage.read();
        final SystemNightMode current = mAccess.read();
        final SystemNightMode desired = preference.nightMode();
        final SystemNightMode previous = pending != null && current == pending.applied()
                ? pending.previous() : current;
        if (current == desired) {
            if (pending != null && current != pending.applied()) mStorage.write(null);
            return true;
        }
        // Write ahead of the system call: process death or a lost Binder reply
        // must leave enough information to recover the original policy.
        mStorage.write(new Override(previous, desired));
        mAccess.write(desired);
        if (mAccess.read() != desired) throw new IOException("Android did not retain the requested system theme");
        return true;
    }

    void systemChanged() throws IOException {
        final Override pending = mStorage.read();
        if (pending != null && mAccess.read() != pending.applied()) {
            mStorage.write(null);
        }
    }

    private void restore() throws IOException {
        final Override pending = mStorage.read();
        if (pending == null) return;
        if (mAccess.read() == pending.applied() && pending.previous() != pending.applied()) {
            mAccess.write(pending.previous());
            if (mAccess.read() != pending.previous()) throw new IOException("Android did not restore the system theme");
        }
        mStorage.write(null);
    }
}
