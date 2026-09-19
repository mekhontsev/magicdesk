package io.github.mekhontsev.magicdesk.wayland;

final class FrameCredit {
    private long sequence, pending;
    private boolean dirty;

    long offer() {
        if (pending != 0) { dirty = true; return 0; }
        pending = ++sequence;
        return pending;
    }

    long pending() { return pending; }

    boolean acknowledge(long serial) {
        if (serial == 0 || serial != pending) return false;
        pending = 0;
        boolean refresh = dirty;
        dirty = false;
        return refresh;
    }
}