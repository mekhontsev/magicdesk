package com.termux.terminal;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/** Static, inline Kitty graphics. Unsupported transports cannot reach a filesystem or shared memory. */
final class KittyGraphicsDecoder {
    private static final int MAX_TRANSFER = TerminalImage.MAX_PIXELS * 4;
    private final TerminalEmulator terminal;
    private final TerminalOutput output;
    private final TerminalGraphics graphics;
    private Map<Character, String> pending;
    private ByteArrayOutputStream transfer;
    private boolean discarding;

    KittyGraphicsDecoder(TerminalEmulator terminal, TerminalOutput output, TerminalGraphics graphics) {
        this.terminal = terminal; this.output = output; this.graphics = graphics;
    }

    void reset() { pending = null; transfer = null; discarding = false; }

    void accept(String command) {
        if (!command.startsWith("G")) return;
        Map<Character, String> args = new HashMap<>();
        Map<Character, String> response = args;
        try {
            int separator = command.indexOf(';');
            String header = command.substring(1, separator < 0 ? command.length() : separator);
            if (header.length() > 512) throw new IllegalArgumentException("E2BIG: graphics header");
            if (!header.isEmpty()) for (String field : header.split(",", -1)) {
                if (field.length() < 3 || field.charAt(1) != '=' || args.put(field.charAt(0), field.substring(2)) != null)
                    throw new IllegalArgumentException("EINVAL: graphics header");
            }
            char action = character(args, 'a', 't');
            for (char key : args.keySet()) if ("atfisvopmxywhXYcrCzqdU".indexOf(key) < 0)
                throw new IllegalArgumentException("ENOTSUP: graphics option " + key);
            for (char key : new char[] {'m', 'C', 'U'}) if (number(args, key, 0) > 1)
                throw new IllegalArgumentException("EINVAL: graphics flag");
            if (number(args, 'q', 0) > 2) throw new IllegalArgumentException("EINVAL: graphics quiet mode");
            if (action == 'd') { reset(); graphics.delete(terminal.getScreen(), character(args, 'd', 'a'),
                    unsigned(args, 'i'), number(args, 'p', 0)); return; }
            if (discarding) {
                if (number(args, 'm', 0) == 0) reset();
                return;
            }
            if (pending != null) {
                response = pending;
                for (char key : args.keySet()) if (key != 'm' && key != 'q')
                    throw new IllegalArgumentException("EINVAL: interrupted image transfer");
            } else {
                if (action != 't' && action != 'T' && action != 'p' && action != 'q')
                    throw new IllegalArgumentException("ENOTSUP: graphics action");
                if (character(args, 't', 'd') != 'd') throw new IllegalArgumentException("ENOTSUP: inline images only");
                if (action == 'p') { place(args, unsigned(args, 'i')); reply(args, "OK"); return; }
                pending = args;
                transfer = new ByteArrayOutputStream();
            }
            byte[] part = Base64.getDecoder().decode(separator < 0 ? "" : command.substring(separator + 1));
            if (transfer.size() + part.length > MAX_TRANSFER) throw new IllegalArgumentException("E2BIG: image transfer");
            transfer.write(part, 0, part.length);
            if (number(args, 'm', 0) == 1) return;
            Map<Character, String> complete = pending;
            response = complete;
            byte[] data = transfer.toByteArray();
            reset();
            char compression = character(complete, 'o', '\0');
            if (compression == 'z') data = inflate(data);
            else if (compression != '\0') throw new IllegalArgumentException("ENOTSUP: compression");
            TerminalImage image = decode(complete, data);
            if (character(complete, 'a', 't') != 'q') {
                long id = graphics.put(unsigned(complete, 'i'), image);
                if (character(complete, 'a', 't') == 'T') place(complete, id);
            }
            reply(complete, "OK");
        } catch (IllegalArgumentException ex) {
            String message = ex.getMessage();
            reply(response, message != null && message.matches("E[A-Z0-9]+:.*") ? message : "EINVAL: malformed image");
            boolean more = "1".equals(args.get('m'));
            reset();
            discarding = more;
        }
    }

    private TerminalImage decode(Map<Character, String> args, byte[] data) {
        int format = number(args, 'f', 32);
        if (format == 100) {
            return terminal.getImageFactory().fromPng(data);
        }
        if (format != 24 && format != 32) throw new IllegalArgumentException("ENOTSUP: pixel format");
        int width = number(args, 's', 0), height = number(args, 'v', 0);
        TerminalImage.checkSize(width, height);
        int channels = format / 8;
        if (data.length != width * height * channels) throw new IllegalArgumentException("EINVAL: pixel data length");
        int[] pixels = new int[width * height];
        for (int i = 0, offset = 0; i < pixels.length; i++, offset += channels) {
            int alpha = channels == 4 ? data[offset + 3] & 255 : 255;
            pixels[i] = alpha << 24 | (data[offset] & 255) << 16 | (data[offset + 1] & 255) << 8 | data[offset + 2] & 255;
        }
        return terminal.getImageFactory().fromArgb(width, height, pixels);
    }

    private void place(Map<Character, String> args, long id) {
        TerminalImage image = graphics.image(id);
        if (image == null) throw new IllegalArgumentException("ENOENT: image");
        int x = number(args, 'x', 0), y = number(args, 'y', 0);
        int w = Math.min(number(args, 'w', image.width), image.width - x);
        int h = Math.min(number(args, 'h', image.height), image.height - y);
        if (w <= 0 || h <= 0) throw new IllegalArgumentException("EINVAL: image crop");
        int cw = terminal.getCellWidthPixels(), ch = terminal.getCellHeightPixels();
        int offsetX = number(args, 'X', 0), offsetY = number(args, 'Y', 0);
        if (offsetX >= cw || offsetY >= ch) throw new IllegalArgumentException("EINVAL: cell offset");
        int columns = number(args, 'c', 0), rows = number(args, 'r', 0);
        if (columns > TerminalImage.MAX_DIMENSION || rows > TerminalImage.MAX_DIMENSION)
            throw new IllegalArgumentException("E2BIG: placement");
        float width = columns == 0 ? w : columns * cw - offsetX;
        float height = rows == 0 ? h : rows * ch - offsetY;
        if (columns != 0 && rows == 0) height = width * h / w;
        else if (rows != 0 && columns == 0) width = height * w / h;
        else if (rows != 0) {
            float scale = Math.min(width / w, height / h);
            width = w * scale; height = h * scale;
        }
        boolean virtual = number(args, 'U', 0) == 1;
        if (virtual && (columns == 0 || rows == 0)) throw new IllegalArgumentException("EINVAL: virtual placement grid");
        TerminalGraphics.Placement placement = graphics.place(terminal.getScreen(), id, number(args, 'p', 0),
                terminal.getCursorCol() + (float) offsetX / cw,
                terminal.getCursorRow() + (float) offsetY / ch, virtual ? columns : width / cw, virtual ? rows : height / ch,
                x, y, w, h, signed(args, 'z', 0), false);
        placement.virtual = virtual;
        if (!virtual && number(args, 'C', 0) == 0) terminal.advanceGraphicsCursor(
                (int) Math.ceil((width + offsetX) / cw), (int) Math.ceil((height + offsetY) / ch), false);
    }

    private static byte[] inflate(byte[] compressed) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            byte[] block = new byte[8192];
            while (!inflater.finished()) {
                int count = inflater.inflate(block);
                if (count == 0) throw new IllegalArgumentException("EINVAL: compressed image");
                if (result.size() + count > MAX_TRANSFER) throw new IllegalArgumentException("E2BIG: inflated image");
                result.write(block, 0, count);
            }
            return result.toByteArray();
        } catch (DataFormatException ex) {
            throw new IllegalArgumentException("EINVAL: compressed image", ex);
        } finally { inflater.end(); }
    }

    private void reply(Map<Character, String> args, String status) {
        long id;
        try { id = unsigned(args, 'i'); } catch (IllegalArgumentException ex) { return; }
        int quiet;
        try { quiet = number(args, 'q', 0); } catch (IllegalArgumentException ex) { quiet = 0; }
        if (id == 0 || quiet == 2 || quiet == 1 && status.equals("OK")) return;
        output.write("\033_Gi=" + id + (args.containsKey('p') ? ",p=" + args.get('p') : "") + ";" + status + "\033\\");
    }

    private static char character(Map<Character, String> args, char key, char fallback) {
        String value = args.get(key);
        if (value == null) return fallback;
        if (value.length() != 1) throw new IllegalArgumentException("EINVAL: graphics option");
        return value.charAt(0);
    }
    private static int signed(Map<Character, String> args, char key, int fallback) {
        return args.containsKey(key) ? Integer.parseInt(args.get(key)) : fallback;
    }
    private static int number(Map<Character, String> args, char key, int fallback) {
        int value = signed(args, key, fallback);
        if (value < 0) throw new IllegalArgumentException("EINVAL: negative graphics option");
        return value;
    }
    private static long unsigned(Map<Character, String> args, char key) {
        long value = args.containsKey(key) ? Long.parseLong(args.get(key)) : 0;
        if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("EINVAL: image id");
        return value;
    }
}
