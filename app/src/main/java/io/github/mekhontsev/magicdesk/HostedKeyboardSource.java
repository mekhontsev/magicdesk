package io.github.mekhontsev.magicdesk;

/** Serialized keyboard data source: a host directory or an explicitly selected guest store. */
record HostedKeyboardSource(String directory, String guestStore) {
    static HostedKeyboardSource parse(String value) {
        if (value == null || value.isEmpty()) return new HostedKeyboardSource("", "");
        boolean guest = value.startsWith("guest:");
        String path = guest ? value.substring(6) : value;
        if (!path.startsWith("/") || path.length() > (guest ? 4095 : 4096)
                || path.indexOf('\0') >= 0 || path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0
                || guest && path.equals("/"))
            throw new IllegalArgumentException(guest ? "Enter guest: followed by an absolute Shroot store path"
                    : "Enter an absolute path to the XKB data directory");
        return guest ? new HostedKeyboardSource("/usr/share/X11/xkb", path) : new HostedKeyboardSource(path, "");
    }
    static String normalize(String value) {
        var source = parse(value);
        return source.guestStore.isEmpty() ? source.directory : "guest:" + source.guestStore;
    }

}
