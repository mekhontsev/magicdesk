package io.github.mekhontsev.magicdesk;

/** Prepared filesystem namespace. It neither installs a distribution nor selects privileges. */
public record GuestEnvironment(String store, String home) {
    public GuestEnvironment {
        store = absolute(store, "environment store");
        home = absolute(home, "guest home");
    }

    static String absolute(String value, String label) {
        if (value == null || !value.startsWith("/") || value.equals("/") && label.equals("environment store")
                || value.indexOf('\0') >= 0 || value.length() > 4095)
            throw new IllegalArgumentException("Invalid " + label);
        return value;
    }
}
