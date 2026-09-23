package io.github.mekhontsev.magicdesk.x11;

import java.util.ArrayList;
import java.util.List;

/** Immutable X-root placement and owner-relative family geometry. */
public record X11ShellSurface(long id, Role role, boolean mapped, Rect bounds, Rect paint,
        boolean inputComplete, List<Rect> input, List<Long> strut) {
    public enum Role { DESKTOP, DOCK }
    public record Rect(int left, int top, int right, int bottom) {
        public Rect {
            if (left > right || top > bottom) throw new IllegalArgumentException("Invalid X11 shell rectangle");
        }
    }
    public X11ShellSurface {
        if (id <= 0 || id > 0xffffffffL || role == null || bounds == null || paint == null || strut.size() != 12
                || input.size() > 128 || !inputComplete && !input.isEmpty())
            throw new IllegalArgumentException("Invalid X11 shell surface");
        input = List.copyOf(input); strut = List.copyOf(strut);
    }
    static X11ShellSurface decode(int id, int[] fields) {
        if (fields.length < 23 || (fields.length - 23) % 4 != 0 || fields[0] < 1 || fields[0] > 2)
            throw new IllegalArgumentException("Invalid native X11 shell metadata");
        var strut = new ArrayList<Long>(12);
        for (int i = 11; i < 23; i++) strut.add(Integer.toUnsignedLong(fields[i]));
        var input = new ArrayList<Rect>();
        for (int i = 23; i < fields.length; i += 4) input.add(rect(fields, i));
        return new X11ShellSurface(Integer.toUnsignedLong(id), fields[0] == 1 ? Role.DESKTOP : Role.DOCK,
                fields[1] != 0, new Rect(fields[3], fields[4], Math.addExact(fields[3], fields[5]),
                        Math.addExact(fields[4], fields[6])), rect(fields, 7), fields[2] != 0, input, strut);
    }
    private static Rect rect(int[] fields, int i) { return new Rect(fields[i], fields[i+1], fields[i+2], fields[i+3]); }
}
