package io.github.mekhontsev.magicdesk.wayland;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** text-input-v3 strings are bounded UTF-8 messages, not a single large paste. */
final class WaylandText {
    static final int MAX_BYTES = 4000;
    record Edit(byte[] text, boolean composing, int cursor) { }

    static void send(String text, boolean composing, int cursor, Consumer<Edit> sink) {
        if (text == null || text.indexOf('\0') >= 0 || cursor < 0 || cursor > text.length())
            throw new IllegalArgumentException("Invalid text edit");
        if (cursor > 0 && cursor < text.length() && Character.isLowSurrogate(text.charAt(cursor))
                && Character.isHighSurrogate(text.charAt(cursor - 1))) cursor--;
        if (composing) {
            // An oversized composition remains in Android's editable; its eventual
            // commit is sent in full. Clear the guest preview rather than truncate it.
            int end = end(text, 0);
            byte[] preview = end == text.length() ? bytes(text) : new byte[0];
            int offset = end == text.length() ? bytes(text.substring(0, cursor)).length : 0;
            sink.accept(new Edit(preview, true, offset));
            return;
        }
        int start = 0;
        do {
            int end = end(text, start);
            byte[] part = bytes(text.substring(start, end));
            sink.accept(new Edit(part, false, part.length));
            start = end;
        } while (start < text.length());
    }

    private static int end(String text, int start) {
        int index = start, size = 0;
        while (index < text.length()) {
            int code = text.codePointAt(index);
            int count = code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
            if (size + count > MAX_BYTES) break;
            size += count;
            index += Character.charCount(code);
        }
        return index;
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private WaylandText() { }
}
