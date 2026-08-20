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
        final List<String> expanded = new ArrayList<>();
        for (final String token : tokenize(normalized)) {
            expandToken(
                    token,
                    supplied,
                    name == null ? "" : name,
                    icon == null ? "" : icon,
                    desktopFilePath == null ? "" : desktopFilePath,
                    expanded);
        }
        final StringBuilder result = new StringBuilder();
        for (final String token : expanded) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(ShellCommandLine.quote(token));
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
            final DesktopLaunchArguments arguments,
            final String name,
            final String icon,
            final String desktopFilePath,
            final List<String> result) {
        if ("%F".equals(token)) {
            result.addAll(arguments.filePaths());
            return;
        }
        if ("%U".equals(token)) {
            result.addAll(arguments.uris());
            return;
        }
        if ("%i".equals(token)) {
            if (!icon.isEmpty()) {
                result.add("--icon");
                result.add(icon);
            }
            return;
        }
        final List<String> files = arguments.filePaths();
        final List<String> uris = arguments.uris();
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
                        expanded.append(files.get(0));
                    }
                    break;
                case 'u':
                    if (uris.isEmpty()) {
                        omittedValue = true;
                    } else {
                        expanded.append(uris.get(0));
                    }
                    break;
                case 'c':
                    expanded.append(name);
                    break;
                case 'k':
                    if (desktopFilePath.isEmpty()) {
                        omittedValue = true;
                    } else {
                        expanded.append(desktopFilePath);
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
            result.add(expanded.toString());
        }
    }
}
