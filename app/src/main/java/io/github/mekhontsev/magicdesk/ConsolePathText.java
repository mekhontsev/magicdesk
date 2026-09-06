package io.github.mekhontsev.magicdesk;

import java.util.List;

/** Pure text handling for shell paths shown by the Console UI. */
final class ConsolePathText {
    private ConsolePathText() {
    }

    static String quotePaths(final List<String> absolutePaths) {
        if (absolutePaths == null || absolutePaths.isEmpty()) {
            throw new IllegalArgumentException("missing paths");
        }
        final StringBuilder result = new StringBuilder();
        for (final String path : absolutePaths) {
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(ShellCommandLine.quote(
                    ShellFilePathPolicy.normalizeShellAbsolute(path)));
        }
        return result.toString();
    }

    static String resolveSelectedPath(
            final String workingDirectory, final String selectedText) {
        if (selectedText == null) {
            throw new IllegalArgumentException("missing selected path");
        }
        String candidate = selectedText.trim();
        if (candidate.length() >= 2
                && ((candidate.charAt(0) == '\''
                        && candidate.charAt(candidate.length() - 1) == '\'')
                    || (candidate.charAt(0) == '"'
                        && candidate.charAt(candidate.length() - 1) == '"'))) {
            candidate = candidate.substring(1, candidate.length() - 1);
        }
        if (candidate.isEmpty()
                || candidate.indexOf('\n') >= 0
                || candidate.indexOf('\r') >= 0
                || candidate.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid selected path");
        }
        final String absolute = candidate.startsWith("/")
                ? candidate : workingDirectory + "/" + candidate;
        return ShellFilePathPolicy.normalizeShellAbsolute(absolute);
    }
}
