package io.github.mekhontsev.magicdesk.platform.nubia;

/** Resolves where a RedMagic firmware stores one hardware setting group. */
enum RedmagicSettingsNamespace {
    SYSTEM("system"),
    GLOBAL("global");

    final String shellName;

    RedmagicSettingsNamespace(final String shellName) {
        this.shellName = shellName;
    }

    static RedmagicSettingsNamespace select(
            final String output,
            final String first,
            final String second) {
        if (output == null) {
            return null;
        }
        final int systemScore = score(output, SYSTEM, first, second);
        final int globalScore = score(output, GLOBAL, first, second);
        if (systemScore == globalScore) {
            return null;
        }
        return globalScore > systemScore ? GLOBAL : SYSTEM;
    }

    static String value(
            final String output,
            final RedmagicSettingsNamespace namespace,
            final String key) {
        if (output == null || namespace == null || key == null) {
            return null;
        }
        final String prefix = "setting." + namespace.shellName
                + "." + key + "=";
        for (final String line : output.split("\\r?\\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static int score(
            final String output,
            final RedmagicSettingsNamespace namespace,
            final String first,
            final String second) {
        int score = 0;
        if (isPresent(value(output, namespace, first))) {
            score++;
        }
        if (isPresent(value(output, namespace, second))) {
            score++;
        }
        return score;
    }

    private static boolean isPresent(final String value) {
        return value != null && !value.isEmpty() && !"null".equals(value);
    }
}
