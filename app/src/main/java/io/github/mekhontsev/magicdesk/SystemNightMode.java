package io.github.mekhontsev.magicdesk;

/** Configured Android policy, not the current day/night appearance of an automatic mode. */
enum SystemNightMode {
    AUTO(0, -1), LIGHT(1, -1), DARK(2, -1), SCHEDULE(3, 0), BEDTIME(3, 1);

    final int mode;
    final int customType;

    SystemNightMode(final int mode, final int customType) {
        this.mode = mode;
        this.customType = customType;
    }

    static SystemNightMode fromFramework(final int mode, final int customType) {
        for (final SystemNightMode value : values()) {
            if (value.mode == mode && (mode != 3 || value.customType == customType)) return value;
        }
        throw new IllegalArgumentException("Unknown Android night mode " + mode + "/" + customType);
    }
}
