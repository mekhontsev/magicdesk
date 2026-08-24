package io.github.mekhontsev.magicdesk;

import java.util.Locale;

/** User-facing task label derived from terminal-owned process metadata. */
final class TerminalTaskLabel {
    private static final int MAX_TITLE_LENGTH = 96;

    private TerminalTaskLabel() {
    }

    static String resolve(
            final String fallback,
            final TerminalProcessInfo process,
            final String oscTitle) {
        final String base = clean(fallback, MAX_TITLE_LENGTH, "Terminal");
        final String executable = process == null ? "" : process.executable;
        final String primary = executable.isEmpty() || isShell(executable)
                ? base : executable;
        final String title = clean(oscTitle, MAX_TITLE_LENGTH, "");
        if (title.isEmpty()
                || title.equalsIgnoreCase(primary)
                || title.equalsIgnoreCase(base)) {
            return primary;
        }
        if (!executable.isEmpty()
                && title.regionMatches(
                        true, 0, executable, 0, executable.length())
                && title.length() > executable.length()
                && !Character.isLetterOrDigit(
                        title.charAt(executable.length()))) {
            return title;
        }
        return primary + " - " + title;
    }

    private static boolean isShell(final String executable) {
        final String normalized = executable.toLowerCase(Locale.ROOT);
        return "sh".equals(normalized)
                || "bash".equals(normalized)
                || "zsh".equals(normalized)
                || "fish".equals(normalized)
                || "dash".equals(normalized)
                || "ksh".equals(normalized)
                || "mksh".equals(normalized);
    }

    private static String clean(
            final String value,
            final int maxLength,
            final String fallback) {
        if (value == null) {
            return fallback;
        }
        final StringBuilder clean = new StringBuilder(Math.min(
                value.length(), maxLength));
        boolean pendingSpace = false;
        for (int index = 0;
                index < value.length() && clean.length() < maxLength;
                index++) {
            final char character = value.charAt(index);
            if (Character.isWhitespace(character)
                    || Character.isISOControl(character)) {
                pendingSpace = clean.length() > 0;
                continue;
            }
            if (pendingSpace && clean.length() < maxLength) {
                clean.append(' ');
            }
            pendingSpace = false;
            if (clean.length() < maxLength) {
                clean.append(character);
            }
        }
        final String result = clean.toString().trim();
        return result.isEmpty() ? fallback : result;
    }
}
