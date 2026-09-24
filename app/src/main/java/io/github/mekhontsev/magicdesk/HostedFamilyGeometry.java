package io.github.mekhontsev.magicdesk;

import java.util.List;

/** Protocol-local dependent bounds, relative to the owner's content origin. */
record HostedFamilyGeometry(int width, int height, ShellBounds paint, boolean inputComplete, List<ShellBounds> input) {
    HostedFamilyGeometry {
        if (width < 0 || height < 0 || paint == null || !inputComplete && !input.isEmpty())
            throw new IllegalArgumentException("Invalid dependent family geometry");
        input = List.copyOf(input);
    }

    ShellBounds place(int hostWidth, int hostHeight) {
        var fit = HostedViewport.fit(hostWidth, hostHeight, width, height);
        if (!fit.available() || paint.isEmpty()) return new ShellBounds(0, 0, 0, 0);
        double scale = fit.width() / width;
        return new ShellBounds((int)Math.floor(fit.left() + paint.left() * scale),
                (int)Math.floor(fit.top() + paint.top() * scale),
                (int)Math.ceil(fit.left() + paint.right() * scale),
                (int)Math.ceil(fit.top() + paint.bottom() * scale));
    }

    ShellBounds place(int hostWidth, int hostHeight, int originX, int originY, ShellBounds area) {
        var natural = place(hostWidth, hostHeight);
        if (natural.isEmpty() || area.isEmpty()) return new ShellBounds(0, 0, 0, 0);
        double scale = Math.min(1, Math.min(area.width() / (double)natural.width(),
                area.height() / (double)natural.height()));
        int width = Math.max(1, (int)Math.floor(natural.width() * scale));
        int height = Math.max(1, (int)Math.floor(natural.height() * scale));
        int left = Math.max(area.left(), Math.min(originX + natural.left(), area.right() - width)) - originX;
        int top = Math.max(area.top(), Math.min(originY + natural.top(), area.bottom() - height)) - originY;
        return new ShellBounds(left, top, left + width, top + height);
    }
}
