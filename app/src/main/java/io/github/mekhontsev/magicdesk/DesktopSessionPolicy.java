package io.github.mekhontsev.magicdesk;

/** Defines whether a desktop session may reuse and persist user workspace state. */
enum DesktopSessionPolicy {
    USER(true, true),
    ISOLATED_SELF_TEST(false, false);

    final boolean restoreWorkspace;
    final boolean persistWorkspace;

    DesktopSessionPolicy(
            final boolean restoreWorkspace,
            final boolean persistWorkspace) {
        this.restoreWorkspace = restoreWorkspace;
        this.persistWorkspace = persistWorkspace;
    }

    static DesktopSessionPolicy parse(final String value) {
        if (value != null && !value.isEmpty()) {
            try {
                return valueOf(value);
            } catch (IllegalArgumentException ignored) {
                // Unknown launch values use normal user-session behavior.
            }
        }
        return USER;
    }
}
