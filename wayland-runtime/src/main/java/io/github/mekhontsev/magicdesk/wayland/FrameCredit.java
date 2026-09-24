package io.github.mekhontsev.magicdesk.wayland;

final class FrameCredit {
    enum Acknowledgement { STALE, CONSUMED, REFRESH }
    private long sequence, pending;
    private boolean dirty;

    boolean canRender() {
        if (pending != 0) { dirty = true; return false; }
        return true;
    }

    long offer() {
        if (!canRender()) return 0;
        pending = ++sequence;
        return pending;
    }

    long pending() { return pending; }

    Acknowledgement acknowledge(long serial) {
        if (serial == 0 || serial != pending) return Acknowledgement.STALE;
        pending = 0;
        boolean refresh = dirty;
        dirty = false;
        return refresh ? Acknowledgement.REFRESH : Acknowledgement.CONSUMED;
    }
}
