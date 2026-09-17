package io.github.mekhontsev.magicdesk.x11;

/** Owner authorization and startup/stop ordering, independent of Binder and native calls. */
final class X11ServerLifecycle {
    enum Stop { NONE, EXIT, NATIVE }
    private enum State { WAITING, RETAINED, STARTING, READY, STOPPING }
    private final int ownerUid;
    private State state = State.WAITING;

    X11ServerLifecycle(int ownerUid) { this.ownerUid = ownerUid; }
    synchronized void checkCaller(int uid) {
        if (uid != ownerUid) throw new SecurityException("Not the X11 host UID");
    }
    synchronized void checkReady(int uid) {
        checkCaller(uid);
        if (state != State.READY) throw new IllegalStateException("X11 server is not ready");
    }
    synchronized boolean retained() { return state != State.WAITING; }
    synchronized void retain() {
        if (state != State.WAITING) throw new IllegalStateException("X11 owner already resolved");
        state = State.RETAINED;
    }
    synchronized boolean beginStart() {
        if (state != State.RETAINED) return false;
        state = State.STARTING;
        return true;
    }
    synchronized boolean ready() {
        if (state != State.STARTING) return false;
        state = State.READY;
        return true;
    }
    synchronized Stop stop() {
        State previous = state;
        state = State.STOPPING;
        return switch (previous) {
            case WAITING, RETAINED -> Stop.EXIT;
            case STARTING, STOPPING -> Stop.NONE;
            case READY -> Stop.NATIVE;
        };
    }
}
