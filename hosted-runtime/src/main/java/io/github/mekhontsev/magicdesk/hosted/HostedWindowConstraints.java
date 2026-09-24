package io.github.mekhontsev.magicdesk.hosted;

/** Client content limits in protocol units; zero is unspecified. Android decorations are separate. */
public record HostedWindowConstraints(int minWidth, int minHeight, int maxWidth, int maxHeight) {
    public static final HostedWindowConstraints NONE = new HostedWindowConstraints(0, 0, 0, 0);

    public HostedWindowConstraints {
        if (minWidth < 0 || minHeight < 0 || maxWidth < 0 || maxHeight < 0
                || maxWidth != 0 && maxWidth < minWidth || maxHeight != 0 && maxHeight < minHeight)
            throw new IllegalArgumentException("Invalid client size constraints");
    }
    public int width(int offered) { return constrain(offered, minWidth, maxWidth); }
    public int height(int offered) { return constrain(offered, minHeight, maxHeight); }
    private static int constrain(int offered, int min, int max) {
        return Math.max(Math.max(1, min), max == 0 ? offered : Math.min(offered, max));
    }
}
