package io.github.mekhontsev.magicdesk;

/** Prepared filesystem namespace. It neither installs a distribution nor selects privileges. */
public record GuestEnvironment(String store, String home, String user, boolean image) {
    public GuestEnvironment(String store, String home, String user) { this(store, home, user, false); }
    public GuestEnvironment {
        store = absolute(store, "environment store");
        home = image && (home == null || home.isEmpty()) ? "" : absolute(home, "guest home");
        user = user == null ? "" : user.trim();
        if (!user.isEmpty() && !user.matches("[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}\\$?(?::[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}\\$?)?"))
            throw new IllegalArgumentException("Invalid guest user or group");
    }

    static String absolute(String value, String label) {
        if (value == null || !value.startsWith("/") || value.equals("/") && label.equals("environment store")
                || value.indexOf('\0') >= 0 || value.length() > 4095)
            throw new IllegalArgumentException("Invalid " + label);
        return value;
    }
}
