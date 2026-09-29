package io.github.mekhontsev.magicdesk;

/** A launch namespace chooses its connection contract independently of executor authority. */
enum WaylandConnectionMode {
    AUTO("auto"), INHERITED("inherited");
    final String wireName;
    WaylandConnectionMode(String wireName) { this.wireName = wireName; }
    boolean namedEndpoint(int clientUid, int serverUid) {
        return this == AUTO && (clientUid == serverUid || clientUid == 0);
    }
    static WaylandConnectionMode parse(String value) {
        if (value == null || value.isEmpty()) return AUTO;
        for (var mode : values()) if (mode.wireName.equals(value)) return mode;
        throw new IllegalArgumentException("Invalid Wayland connection mode");
    }
}
