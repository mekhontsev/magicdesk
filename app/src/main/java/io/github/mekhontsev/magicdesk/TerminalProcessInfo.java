package io.github.mekhontsev.magicdesk;

import java.util.Objects;

/** Immutable foreground-process metadata reported by a terminal transport. */
final class TerminalProcessInfo {
    private static final int MAX_EXECUTABLE_LENGTH = 128;
    private static final TerminalProcessInfo UNKNOWN =
            new TerminalProcessInfo(-1L, -1L, "");

    final long processId;
    final long processGroupId;
    final String executable;

    TerminalProcessInfo(
            final long processId,
            final long processGroupId,
            final String executable) {
        this.processId = processId > 0L ? processId : -1L;
        this.processGroupId = processGroupId > 0L ? processGroupId : -1L;
        this.executable = normalizeExecutable(executable);
    }

    static TerminalProcessInfo unknown() {
        return UNKNOWN;
    }

    boolean isKnown() {
        return processId > 0L && !executable.isEmpty();
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TerminalProcessInfo)) {
            return false;
        }
        final TerminalProcessInfo process = (TerminalProcessInfo) other;
        return processId == process.processId
                && processGroupId == process.processGroupId
                && executable.equals(process.executable);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                Long.valueOf(processId),
                Long.valueOf(processGroupId),
                executable);
    }

    private static String normalizeExecutable(final String value) {
        if (value == null) {
            return "";
        }
        String name = value.trim();
        final int separator = name.lastIndexOf('/');
        if (separator >= 0) {
            name = name.substring(separator + 1);
        }
        final StringBuilder clean = new StringBuilder(Math.min(
                name.length(), MAX_EXECUTABLE_LENGTH));
        for (int index = 0;
                index < name.length()
                        && clean.length() < MAX_EXECUTABLE_LENGTH;
                index++) {
            final char character = name.charAt(index);
            if (!Character.isISOControl(character)) {
                clean.append(character);
            }
        }
        return clean.toString().trim();
    }
}
