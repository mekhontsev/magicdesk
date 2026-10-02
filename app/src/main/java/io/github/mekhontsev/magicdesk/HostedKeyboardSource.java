package io.github.mekhontsev.magicdesk;

/** Serialized keyboard data source: a host directory or an explicitly selected guest store. */
record HostedKeyboardSource(String directory, String guestStore) {
    static HostedKeyboardSource parse(String value) {
        if (value != null && value.startsWith("guest:"))
            return new HostedKeyboardSource("/usr/share/X11/xkb", GuestEnvironment.absolute(value.substring(6), "environment store"));
        return new HostedKeyboardSource(DesktopExecWorkingDirectory.normalize(value), "");
    }
    static String normalize(String value) {
        var source = parse(value);
        return source.guestStore.isEmpty() ? source.directory : "guest:" + source.guestStore;
    }
}
