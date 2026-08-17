package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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

    static String insert(
            final String text,
            final int selectionStart,
            final int selectionEnd,
            final String insertion) {
        final String source = text == null ? "" : text;
        final int start = Math.max(0,
                Math.min(Math.min(selectionStart, selectionEnd), source.length()));
        final int end = Math.max(start,
                Math.min(Math.max(selectionStart, selectionEnd), source.length()));
        final StringBuilder result = new StringBuilder(
                source.length() + insertion.length() + 2);
        result.append(source, 0, start);
        if (start > 0 && !Character.isWhitespace(source.charAt(start - 1))) {
            result.append(' ');
        }
        result.append(insertion);
        if (end < source.length()
                && !Character.isWhitespace(source.charAt(end))) {
            result.append(' ');
        }
        result.append(source, end, source.length());
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

    static CompletionRequest completionRequest(
            final String command,
            final int cursor,
            final String workingDirectory) {
        if (command == null || cursor < 0 || cursor > command.length()) {
            return null;
        }
        final int start = wordStart(command, cursor);
        final int end = wordEnd(command, start, cursor);
        final String encoded = command.substring(start, cursor);
        String token = unwrapIncompleteQuote(encoded);
        if (token.isEmpty() || (start == 0 && token.indexOf('/') < 0)) {
            return null;
        }
        final int separator = token.lastIndexOf('/');
        final String parent;
        final String replacementPrefix;
        final String namePrefix;
        if (token.startsWith("/")) {
            parent = separator == 0 ? "/" : token.substring(0, separator);
            replacementPrefix = token.substring(0, separator + 1);
            namePrefix = token.substring(separator + 1);
        } else if (separator >= 0) {
            final String relativeParent = token.substring(0, separator);
            parent = ShellFilePathPolicy.normalizeShellAbsolute(
                    workingDirectory + "/" + relativeParent);
            replacementPrefix = token.substring(0, separator + 1);
            namePrefix = token.substring(separator + 1);
        } else {
            parent = ShellFilePathPolicy.normalizeShellAbsolute(
                    workingDirectory);
            replacementPrefix = "";
            namePrefix = token;
        }
        return new CompletionRequest(
                start, end, parent, replacementPrefix, namePrefix);
    }

    static CompletionResult complete(
            final CompletionRequest request,
            final List<ShellFileInfo> entries) {
        if (request == null || entries == null) {
            return null;
        }
        final List<ShellFileInfo> matches = new ArrayList<>();
        for (final ShellFileInfo entry : entries) {
            if (entry.name.startsWith(request.namePrefix)) {
                matches.add(entry);
            }
        }
        Collections.sort(matches, Comparator.comparing(file -> file.name));
        if (matches.isEmpty()) {
            return new CompletionResult(null, Collections.emptyList());
        }
        final String common = longestCommonPrefix(matches);
        if (matches.size() > 1 && common.length() <= request.namePrefix.length()) {
            final List<String> names = new ArrayList<>(matches.size());
            for (final ShellFileInfo match : matches) {
                names.add(match.name + (match.directory ? "/" : ""));
            }
            return new CompletionResult(null, names);
        }
        final ShellFileInfo only = matches.size() == 1 ? matches.get(0) : null;
        final String completedName = only == null ? common : only.name;
        final String completedPath = request.replacementPrefix
                + completedName
                + (only != null && only.directory ? "/" : "");
        return new CompletionResult(
                ShellCommandLine.quote(completedPath), Collections.emptyList());
    }

    private static String unwrapIncompleteQuote(final String encoded) {
        if (encoded.length() >= 2
                && encoded.charAt(0) == '\''
                && encoded.charAt(encoded.length() - 1) == '\'') {
            return encoded.substring(1, encoded.length() - 1)
                    .replace("'\"'\"'", "'");
        }
        if (!encoded.isEmpty()
                && (encoded.charAt(0) == '\'' || encoded.charAt(0) == '"')) {
            return encoded.substring(1);
        }
        return encoded.replace("\\ ", " ");
    }

    private static int wordStart(final String command, final int cursor) {
        int start = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < cursor; index++) {
            final char value = command.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (value == '\\' && quote != '\'') {
                escaped = true;
            } else if (quote == 0 && (value == '\'' || value == '"')) {
                quote = value;
            } else if (quote == value) {
                quote = 0;
            } else if (quote == 0 && Character.isWhitespace(value)) {
                start = index + 1;
            }
        }
        return start;
    }

    private static int wordEnd(
            final String command, final int start, final int cursor) {
        char quote = 0;
        boolean escaped = false;
        for (int index = start; index < command.length(); index++) {
            final char value = command.charAt(index);
            if (escaped) {
                escaped = false;
            } else if (value == '\\' && quote != '\'') {
                escaped = true;
            } else if (quote == 0 && (value == '\'' || value == '"')) {
                quote = value;
            } else if (quote == value) {
                quote = 0;
            } else if (index >= cursor
                    && quote == 0
                    && Character.isWhitespace(value)) {
                return index;
            }
        }
        return command.length();
    }

    private static String longestCommonPrefix(
            final List<ShellFileInfo> matches) {
        String prefix = matches.get(0).name;
        for (int index = 1; index < matches.size(); index++) {
            final String name = matches.get(index).name;
            int length = Math.min(prefix.length(), name.length());
            int common = 0;
            while (common < length
                    && prefix.charAt(common) == name.charAt(common)) {
                common++;
            }
            prefix = prefix.substring(0, common);
        }
        return prefix;
    }

    static final class CompletionRequest {
        final int tokenStart;
        final int tokenEnd;
        final String parentPath;
        final String replacementPrefix;
        final String namePrefix;

        CompletionRequest(
                final int tokenStart,
                final int tokenEnd,
                final String parentPath,
                final String replacementPrefix,
                final String namePrefix) {
            this.tokenStart = tokenStart;
            this.tokenEnd = tokenEnd;
            this.parentPath = parentPath;
            this.replacementPrefix = replacementPrefix;
            this.namePrefix = namePrefix;
        }
    }

    static final class CompletionResult {
        final String replacement;
        final List<String> alternatives;

        CompletionResult(
                final String replacement,
                final List<String> alternatives) {
            this.replacement = replacement;
            this.alternatives = Collections.unmodifiableList(
                    new ArrayList<>(alternatives));
        }
    }
}
