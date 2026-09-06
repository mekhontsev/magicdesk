package io.github.mekhontsev.magicdesk;

/** Bounded text projection without splitting a valid UTF-16 surrogate pair. */
final class BoundedText {
    private BoundedText() {
    }

    static String prefix(final CharSequence text, final int maxChars) {
        if (maxChars < 0) {
            throw new IllegalArgumentException("negative text limit");
        }
        if (text == null || maxChars == 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text.toString();
        }
        int end = maxChars;
        if (Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        // Limit the CharSequence before materializing it, not after a full copy.
        return text.subSequence(0, end).toString();
    }
}
