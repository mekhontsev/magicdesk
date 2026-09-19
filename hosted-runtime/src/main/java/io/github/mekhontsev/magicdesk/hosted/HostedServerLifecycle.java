package io.github.mekhontsev.magicdesk.hosted;

public final class HostedServerLifecycle {
    public enum Stop { NONE, EXIT, NATIVE }
    private enum State { WAITING, RETAINED, STARTING, READY, STOPPING }
    private final int ownerUid;
    private State state = State.WAITING;

    public HostedServerLifecycle(int ownerUid) { this.ownerUid = ownerUid; }
    public synchronized void checkCaller(int uid) {
        if (uid != ownerUid) throw new SecurityException("Not the hosted server owner UID");
    }
    public synchronized void checkReady(int uid) {
        checkCaller(uid);
        if (state != State.READY) throw new IllegalStateException("Hosted server is not ready");
    }
    public synchronized boolean retained() { return state != State.WAITING; }
    public synchronized void retain() {
        if (state != State.WAITING) throw new IllegalStateException("Hosted server owner already resolved");
        state = State.RETAINED;
    }
    public synchronized boolean beginStart() {
        if (state != State.RETAINED) return false;
        state = State.STARTING;
        return true;
    }
    public synchronized boolean ready() {
        if (state != State.STARTING) return false;
        state = State.READY;
        return true;
    }
    public synchronized Stop stop() {
        State previous = state;
        state = State.STOPPING;
        return switch (previous) {
            case WAITING, RETAINED -> Stop.EXIT;
            case STARTING, STOPPING -> Stop.NONE;
            case READY -> Stop.NATIVE;
        };
    }
}