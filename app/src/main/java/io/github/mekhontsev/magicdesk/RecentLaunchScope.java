package io.github.mekhontsev.magicdesk;

/** History follows launch ownership, never the Start host or the current privilege mode. */
enum RecentLaunchScope {
    DESKTOP("desktop"), INDEPENDENT("independent");

    final String directory;
    RecentLaunchScope(String directory) { this.directory = directory; }

    static RecentLaunchScope of(ToolLaunchTarget target) {
        return target.desktop ? DESKTOP : INDEPENDENT;
    }
}
