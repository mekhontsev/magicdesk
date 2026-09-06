package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;

/** Desktop Entry field-code expansion with shell-safe argument rendering. */
final class DesktopExecTemplate {
    private DesktopExecTemplate() {
    }

    static String expand(
            final String command,
            final DesktopLaunchArguments arguments,
            final String name,
            final String icon,
            final String desktopFilePath) {
        final String normalized = DesktopExecCommand.normalize(command);
        if (!hasFieldCodes(normalized)) {
            return DesktopExecCommand.normalize(
                    normalized.replace("%%", "%"));
        }
        final DesktopLaunchArguments supplied = arguments == null
                ? DesktopLaunchArguments.empty() : arguments;
        final List<String> files = supplied.filePaths();
        final List<String> uris = supplied.uris();
        final StringBuilder result = new StringBuilder();
        for (final String token : tokenize(normalized)) {
            expandToken(
                    token,
                    files,
                    uris,
                    name == null ? "" : name,
                    icon == null ? "" : icon,
                    desktopFilePath == null ? "" : desktopFilePath,
                    result);
        }
        return DesktopExecCommand.normalize(result.toString());
    }

    static boolean acceptsArguments(final String command) {
        final String normalized = DesktopExecCommand.normalize(command);
        for (int index = 0; index + 1 < normalized.length(); index++) {
            if (normalized.charAt(index) != '%') {
                continue;
            }
            final char code = normalized.charAt(++index);
            if (code == '%') {
                continue;
            }
            if (code == 'f' || code == 'F'
                    || code == 'u' || code == 'U') {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFieldCodes(final String command) {
        for (int index = 0; index < command.length(); index++) {
            if (command.charAt(index) != '%') {
                continue;
            }
            if (++index >= command.length()) {
                throw new IllegalArgumentException("incomplete Exec field code");
            }
            if (command.charAt(index) != '%') {
                return true;
            }
        }
        return false;
    }

    private static List<String> tokenize(final String command) {
        final List<String> tokens = new ArrayList<>();
        final StringBuilder token = new StringBuilder();
        boolean started = false;
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;
        for (int index = 0; index < command.length(); index++) {
            final char character = command.charAt(index);
            if (escaped) {
                token.append(character);
                escaped = false;
                started = true;
                continue;
            }
            if (singleQuoted) {
                if (character == '\'') {
                    singleQuoted = false;
                } else {
                    token.append(character);
                }
                started = true;
                continue;
            }
            if (doubleQuoted) {
                if (character == '"') {
                    doubleQuoted = false;
                } else if (character == '\\') {
                    escaped = true;
                } else {
                    token.append(character);
                }
                started = true;
                continue;
            }
            if (character == '\\') {
                escaped = true;
                started = true;
            } else if (character == '\'') {
                singleQuoted = true;
                started = true;
            } else if (character == '"') {
                doubleQuoted = true;
                started = true;
            } else if (Character.isWhitespace(character)) {
                if (started) {
                    tokens.add(token.toString());
                    token.setLength(0);
                    started = false;
                }
            } else {
                token.append(character);
                started = true;
            }
        }
        if (escaped || singleQuoted || doubleQuoted) {
            throw new IllegalArgumentException("unterminated Exec quoting");
        }
        if (started) {
            tokens.add(token.toString());
        }
        if (tokens.isEmpty()) {
            throw new IllegalArgumentException("empty Exec template");
        }
        return tokens;
    }

    private static void expandToken(
            final String token,
            final List<String> files,
            final List<String> uris,
            final String name,
            final String icon,
            final String desktopFilePath,
            final StringBuilder result) {
        if ("%F".equals(token)) {
            for (final String file : files) {
                appendArgument(result, file);
            }
            return;
        }
        if ("%U".equals(token)) {
            for (final String uri : uris) {
                appendArgument(result, uri);
            }
            return;
        }
        if ("%i".equals(token)) {
            if (!icon.isEmpty()) {
                appendArgument(result, "--icon");
                appendArgument(result, icon);
            }
            return;
        }
        final StringBuilder expanded = new StringBuilder();
        boolean omittedValue = false;
        boolean hadField = false;
        for (int index = 0; index < token.length(); index++) {
            final char character = token.charAt(index);
            if (character != '%') {
                expanded.append(character);
                continue;
            }
            if (++index >= token.length()) {
                throw new IllegalArgumentException("incomplete Exec field code");
            }
            hadField = true;
            switch (token.charAt(index)) {
                case '%':
                    expanded.append('%');
                    break;
                case 'f':
                    if (files.isEmpty()) {
                        omittedValue = true;
                    } else {
                        appendField(expanded, files.get(0));
                    }
                    break;
                case 'u':
                    if (uris.isEmpty()) {
                        omittedValue = true;
                    } else {
                        appendField(expanded, uris.get(0));
                    }
                    break;
                case 'c':
                    appendField(expanded, name);
                    break;
                case 'k':
                    if (desktopFilePath.isEmpty()) {
                        omittedValue = true;
                    } else {
                        appendField(expanded, desktopFilePath);
                    }
                    break;
                case 'd':
                case 'D':
                case 'n':
                case 'N':
                case 'v':
                case 'm':
                    break;
                case 'F':
                case 'U':
                case 'i':
                    throw new IllegalArgumentException(
                            "multi-value Exec field code must be a full argument");
                default:
                    throw new IllegalArgumentException(
                            "unsupported Exec field code");
            }
        }
        if (!(hadField && omittedValue && expanded.length() == 0)) {
            appendArgument(result, expanded.toString());
        }
    }

    private static void appendField(final StringBuilder token, final String value) {
        if (value.length() > DesktopExecCommand.MAX_LENGTH - token.length()) {
            throw new IllegalArgumentException("expanded Exec argument is too long");
        }
        token.append(value);
    }

    private static void appendArgument(final StringBuilder command, final String value) {
        final int separatorLength = command.length() == 0 ? 0 : 1;
        final int remaining = DesktopExecCommand.MAX_LENGTH - command.length() - separatorLength;
        if (value.length() > remaining - 2) {
            throw new IllegalArgumentException("expanded Exec command is too long");
        }
        // Check each rendered argument, not a complete expansion that may multiply
        // the same selection many times through repeated %F or %U fields.
        final String quoted = ShellCommandLine.quote(value);
        if (quoted.length() > remaining) {
            throw new IllegalArgumentException("expanded Exec command is too long");
        }
        if (separatorLength != 0) {
            command.append(' ');
        }
        command.append(quoted);
    }
}
