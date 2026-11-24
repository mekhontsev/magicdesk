package io.github.mekhontsev.magicdesk.wayland;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import io.github.mekhontsev.magicdesk.hosted.HostedTextState;

/** text-input-v3 strings are bounded UTF-8 messages, not a single large paste. */
final class WaylandText {
    static final int MAX_BYTES = 4000;
    record Edit(byte[] text, boolean composing, int cursor) { }
    record Deletion(int before, int after) { }

    static HostedTextState state(long editor, long revision, byte[] surrounding, int cursor, int anchor,
            int purpose, int hints) {
        if (editor == 0) return null;
        String text = surrounding == null ? null : new String(surrounding, StandardCharsets.UTF_8);
        return new HostedTextState(editor, revision, purpose(purpose), hints, text,
                text == null ? -1 : utf16(surrounding, cursor), text == null ? -1 : utf16(surrounding, anchor));
    }

    private static HostedTextState.Purpose purpose(int wire) {
        // text-input-v3 wire values belong to this adapter, not the shared enum order.
        return switch (wire) {
            case 0 -> HostedTextState.Purpose.NORMAL;
            case 1 -> HostedTextState.Purpose.ALPHA;
            case 2 -> HostedTextState.Purpose.DIGITS;
            case 3 -> HostedTextState.Purpose.NUMBER;
            case 4 -> HostedTextState.Purpose.PHONE;
            case 5 -> HostedTextState.Purpose.URL;
            case 6 -> HostedTextState.Purpose.EMAIL;
            case 7 -> HostedTextState.Purpose.NAME;
            case 8 -> HostedTextState.Purpose.PASSWORD;
            case 9 -> HostedTextState.Purpose.PIN;
            case 10 -> HostedTextState.Purpose.DATE;
            case 11 -> HostedTextState.Purpose.TIME;
            case 12 -> HostedTextState.Purpose.DATETIME;
            case 13 -> HostedTextState.Purpose.TERMINAL;
            default -> throw new IllegalArgumentException("Invalid text-input purpose");
        };
    }

    private static int utf16(byte[] text, int offset) {
        if (text.length > MAX_BYTES || offset < 0 || offset > text.length
                || (offset < text.length && (text[offset] & 0xc0) == 0x80))
            throw new IllegalArgumentException("Invalid text-input offset");
        return new String(text, 0, offset, StandardCharsets.UTF_8).length();
    }

    static Deletion deletion(HostedTextState state, int before, int after, boolean codePoints) {
        if (state == null || state.surrounding() == null || before < 0 || after < 0) return null;
        String text = state.surrounding();
        int start = state.start(), end = state.end();
        int left, right;
        if (codePoints) {
            left = text.offsetByCodePoints(start, -Math.min(before, text.codePointCount(0, start)));
            right = text.offsetByCodePoints(end, Math.min(after, text.codePointCount(end, text.length())));
        } else {
            left = start - Math.min(before, start);
            right = end + Math.min(after, text.length() - end);
            if (left > 0 && left < text.length() && Character.isLowSurrogate(text.charAt(left))) left--;
            if (right < text.length() && Character.isLowSurrogate(text.charAt(right))) right++;
        }
        return new Deletion(bytes(text.substring(left, start)).length, bytes(text.substring(end, right)).length);
    }

    static void send(String text, boolean composing, int cursor, Consumer<Edit> sink) {
        if (composing) {
            sink.accept(edit(text, true, cursor));
            return;
        }
        validate(text, cursor);
        int start = 0;
        do {
            int end = end(text, start);
            byte[] part = bytes(text.substring(start, end));
            sink.accept(new Edit(part, false, part.length));
            start = end;
        } while (start < text.length());
    }

    static Edit edit(String text, boolean composing, int cursor) {
        validate(text, cursor);
        if (end(text, 0) != text.length()) {
            // A replacement with a commit must fit one batch. An oversized
            // composition can stay in Android, with no preview in the guest.
            return composing ? new Edit(new byte[0], true, 0) : null;
        }
        byte[] encoded = bytes(text);
        if (!composing) return new Edit(encoded, false, encoded.length);
        if (cursor > 0 && cursor < text.length()
                && Character.isSurrogatePair(text.charAt(cursor - 1), text.charAt(cursor))) cursor--;
        return new Edit(encoded, true, bytes(text.substring(0, cursor)).length);
    }

    private static void validate(String text, int cursor) {
        if (text == null || text.indexOf('\0') >= 0 || cursor < 0 || cursor > text.length())
            throw new IllegalArgumentException("Invalid text edit");
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
