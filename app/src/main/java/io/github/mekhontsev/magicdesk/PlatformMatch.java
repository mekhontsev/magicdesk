package io.github.mekhontsev.magicdesk;

/** Result of a non-destructive firmware-extension probe. */
public final class PlatformMatch {
    public final boolean matched;
    public final String evidence;

    private PlatformMatch(final boolean matched, final String evidence) {
        this.matched = matched;
        this.evidence = evidence == null ? "" : evidence;
    }

    public static PlatformMatch matched(final String evidence) {
        return new PlatformMatch(true, evidence);
    }

    public static PlatformMatch unavailable(final String evidence) {
        return new PlatformMatch(false, evidence);
    }
}
