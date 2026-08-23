package io.github.mekhontsev.magicdesk;

/** Result of one bounded capability observation. */
public enum PlatformCapabilityState {
    AVAILABLE("available"),
    UNAVAILABLE("unavailable"),
    DENIED("denied"),
    BROKEN("broken"),
    NOT_TESTED("not_tested");

    public final String wireName;

    PlatformCapabilityState(final String wireName) {
        this.wireName = wireName;
    }
}
