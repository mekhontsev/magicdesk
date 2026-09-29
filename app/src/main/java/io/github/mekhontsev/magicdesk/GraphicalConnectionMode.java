package io.github.mekhontsev.magicdesk;

/** A launch namespace chooses its connection contract independently of executor authority. */
enum GraphicalConnectionMode {
    AUTO("auto"), INHERITED("inherited"), ROUTED("routed");
    final String wireName;
    GraphicalConnectionMode(String wireName) { this.wireName = wireName; }
    boolean namedEndpoint(int clientUid, int serverUid) {
        return this == AUTO && (clientUid == serverUid || clientUid == 0);
    }
    static GraphicalConnectionMode parse(String value) {
        if (value == null || value.isEmpty()) return AUTO;
        for (var mode : values()) if (mode.wireName.equals(value)) return mode;
        throw new IllegalArgumentException("Invalid graphical connection mode");
    }
}
