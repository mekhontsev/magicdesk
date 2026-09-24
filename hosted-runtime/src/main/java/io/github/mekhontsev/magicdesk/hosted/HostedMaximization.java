package io.github.mekhontsev.magicdesk.hosted;

/** Independent layout axes; fullscreen is a separate residency and request. */
public enum HostedMaximization {
    NONE(false, false), HORIZONTAL(true, false), VERTICAL(false, true), BOTH(true, true);

    public final boolean horizontal, vertical;
    HostedMaximization(boolean horizontal, boolean vertical) {
        this.horizontal = horizontal; this.vertical = vertical;
    }
    public static HostedMaximization of(boolean horizontal, boolean vertical) {
        return horizontal ? (vertical ? BOTH : HORIZONTAL) : (vertical ? VERTICAL : NONE);
    }
}
