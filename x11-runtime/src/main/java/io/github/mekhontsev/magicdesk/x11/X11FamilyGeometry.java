package io.github.mekhontsev.magicdesk.x11;

import java.util.ArrayList;
import java.util.List;

/** Dependent paint/input in the owning client's coordinates. */
public record X11FamilyGeometry(int width, int height, X11ShellSurface.Rect paint,
        boolean inputComplete, List<X11ShellSurface.Rect> input) {
    public X11FamilyGeometry {
        if (width < 0 || height < 0 || paint == null || input.size() > 128 || !inputComplete && !input.isEmpty())
            throw new IllegalArgumentException("Invalid X11 family geometry");
        input = List.copyOf(input);
    }
    static X11FamilyGeometry decode(int[] fields) {
        if (fields.length < 7 || (fields.length - 7) % 4 != 0)
            throw new IllegalArgumentException("Invalid native X11 family geometry");
        var input = new ArrayList<X11ShellSurface.Rect>();
        for (int i = 7; i < fields.length; i += 4)
            input.add(new X11ShellSurface.Rect(fields[i], fields[i+1], fields[i+2], fields[i+3]));
        return new X11FamilyGeometry(fields[0], fields[1],
                new X11ShellSurface.Rect(fields[3], fields[4], fields[5], fields[6]), fields[2] != 0, input);
    }
}
