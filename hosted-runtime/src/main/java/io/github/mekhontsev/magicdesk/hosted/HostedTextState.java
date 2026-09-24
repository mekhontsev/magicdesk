package io.github.mekhontsev.magicdesk.hosted;

/** Immutable guest editor context. Positions use UTF-16; null text means unavailable, not empty. */
public record HostedTextState(long editor, long revision, Purpose purpose, int hints,
        String surrounding, int cursor, int anchor, Caret caret) {
    /** Rectangle in normalized output coordinates, including out-of-viewport positions. */
    public record Caret(float left, float top, float right, float bottom) {
        public Caret {
            if (!Float.isFinite(left) || !Float.isFinite(top) || !Float.isFinite(right)
                    || !Float.isFinite(bottom) || right < left || bottom < top)
                throw new IllegalArgumentException("Invalid editor rectangle");
        }
    }
    public HostedTextState(long editor, long revision, Purpose purpose, int hints,
            String surrounding, int cursor, int anchor) {
        this(editor, revision, purpose, hints, surrounding, cursor, anchor, null);
    }
    public enum Purpose { NORMAL, ALPHA, DIGITS, NUMBER, PHONE, URL, EMAIL, NAME, PASSWORD, PIN, DATE, TIME, DATETIME, TERMINAL }
    public static final int COMPLETION = 1, SPELLCHECK = 2, AUTO_CAPITALIZE = 4,
            LOWERCASE = 8, UPPERCASE = 16, TITLECASE = 32, HIDDEN = 64,
            SENSITIVE = 128, LATIN = 256, MULTILINE = 512;

    public HostedTextState {
        if (editor <= 0 || purpose == null || (surrounding != null &&
                (!boundary(surrounding, cursor) || !boundary(surrounding, anchor))))
            throw new IllegalArgumentException("Invalid hosted editor context");
        if (surrounding == null) { cursor = -1; anchor = -1; }
    }

    public boolean sameEditor(HostedTextState other) {
        return other != null && editor == other.editor && purpose == other.purpose && hints == other.hints;
    }
    public boolean privateText() {
        return purpose == Purpose.PASSWORD || purpose == Purpose.PIN || (hints & (HIDDEN | SENSITIVE)) != 0;
    }
    public int start() { return Math.min(cursor, anchor); }
    public int end() { return Math.max(cursor, anchor); }

    private static boolean boundary(String text, int index) {
        return index >= 0 && index <= text.length() && !(index > 0 && index < text.length()
                && Character.isHighSurrogate(text.charAt(index - 1)) && Character.isLowSurrogate(text.charAt(index)));
    }
}
