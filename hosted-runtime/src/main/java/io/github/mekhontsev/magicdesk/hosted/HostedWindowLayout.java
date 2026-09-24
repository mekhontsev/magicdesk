package io.github.mekhontsev.magicdesk.hosted;

/** Protocol-local parent identity and client content geometry, independent of Android placement. */
public record HostedWindowLayout(long parent, int width, int height, HostedWindowConstraints constraints) {
    public static final HostedWindowLayout NONE = new HostedWindowLayout(0, 0, 0, HostedWindowConstraints.NONE);
}
