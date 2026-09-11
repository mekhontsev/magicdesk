package io.github.mekhontsev.magicdesk;

import java.util.regex.Pattern;

/** Explicit prose-copy heuristic; never applied to terminal input or exact copy. */
final class ConsoleCopyText {
    private static final Pattern BLOCK_START = Pattern.compile(
            "^(?:[-*+\\u2022]\\s+|\\d+[.)]\\s+|#{1,6}\\s+|>\\s?).*");

    private ConsoleCopyText() {}

    static String asParagraph(final String text) {
        final StringBuilder result = new StringBuilder();
        boolean first = true, previousBlank = false, previousLiteral = false, fenced = false;
        for (final String raw : text.lines().toList()) {
            final String line = raw.strip();
            final boolean blank = line.isEmpty();
            final boolean fence = line.startsWith("```") || line.startsWith("~~~");
            final boolean literal = fenced || fence || raw.startsWith("\t")
                    || raw.startsWith("    ") || line.startsWith("|");
            if (!first) {
                result.append(blank || previousBlank || literal || previousLiteral
                        || BLOCK_START.matcher(line).matches() ? '\n' : ' ');
            }
            result.append(literal ? raw.stripTrailing() : line);
            first = false;
            previousBlank = blank;
            previousLiteral = literal || line.startsWith("#");
            if (fence) fenced = !fenced;
        }
        return result.toString().strip();
    }
}
