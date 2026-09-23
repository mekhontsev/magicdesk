package io.github.mekhontsev.magicdesk;

/** UI-thread receipt join. Every replacement revokes input before accepting new receipts. */
final class ShellFrameAdmission {
    enum Phase { IDLE, PRESENTING, REGION, READY }
    private long generation;
    private Phase phase = Phase.IDLE;
    private boolean layout, pixels;

    long begin() { layout = pixels = false; phase = Phase.PRESENTING; return ++generation; }
    void revoke() { ++generation; phase = Phase.IDLE; }
    long generation() { return generation; }
    Phase phase() { return phase; }
    boolean ready() { return phase == Phase.READY; }
    boolean layout(long receipt) { return presented(receipt, true); }
    boolean pixels(long receipt) { return presented(receipt, false); }
    boolean region(long receipt) {
        if (receipt != generation || phase != Phase.REGION) return false;
        phase = Phase.READY;
        return true;
    }
    boolean current(long receipt) { return receipt == generation && phase != Phase.IDLE; }

    private boolean presented(long receipt, boolean window) {
        if (receipt != generation || phase != Phase.PRESENTING) return false;
        if (window) layout = true; else pixels = true;
        if (!layout || !pixels) return false;
        phase = Phase.REGION;
        return true;
    }
}
