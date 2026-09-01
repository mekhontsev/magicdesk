package io.github.mekhontsev.magicdesk;

import android.graphics.Point;

/** Injects test-only pointer gestures not represented by a production action. */
public final class DesktopPointerCommand {
    private DesktopPointerCommand() {
    }

    public static void main(final String[] args) {
        try {
            if (args.length == 5 && "long-press".equals(args[0])) {
                DesktopPointerInjector.injectSyntheticTouchLongPress(
                        nonNegativeInt(args[1], "display id"),
                        point(args[2], args[3]),
                        nonNegativeLong(args[4], "duration"));
                System.out.println("touch-long-pressed");
                return;
            }
            if (args.length == 7 && "drag".equals(args[0])) {
                DesktopPointerInjector.injectMouseDrag(
                        nonNegativeInt(args[1], "display id"),
                        point(args[2], args[3]),
                        point(args[4], args[5]),
                        nonNegativeLong(args[6], "duration"));
                System.out.println("pointer-dragged");
                return;
            }
            System.err.println("usage: DesktopPointerCommand "
                    + "<long-press display x y duration-ms|"
                    + "drag display start-x start-y "
                    + "end-x end-y duration-ms>");
            System.exit(64);
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("pointer command failed: " + error);
            System.exit(1);
        }
    }

    private static Point point(final String x, final String y) {
        return new Point(nonNegativeInt(x, "x"), nonNegativeInt(y, "y"));
    }

    private static int nonNegativeInt(final String value, final String label) {
        final int parsed = Integer.parseInt(value);
        if (parsed < 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
    }

    private static long nonNegativeLong(final String value, final String label) {
        final long parsed = Long.parseLong(value);
        if (parsed < 0L) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
    }
}
