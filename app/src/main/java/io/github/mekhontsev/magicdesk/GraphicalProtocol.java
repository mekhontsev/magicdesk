package io.github.mekhontsev.magicdesk;

enum GraphicalProtocol {
    X11("x11"), WAYLAND("wayland");

    final String wireName;
    GraphicalProtocol(String wireName) { this.wireName = wireName; }

    static GraphicalProtocol parse(String value) {
        for (var protocol : values()) if (protocol.wireName.equals(value)) return protocol;
        throw new IllegalArgumentException("Unknown graphical protocol: " + value);
    }
}
