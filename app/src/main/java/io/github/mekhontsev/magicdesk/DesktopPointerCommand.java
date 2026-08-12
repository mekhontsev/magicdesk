package io.github.mekhontsev.magicdesk;

import android.graphics.Point;
import android.view.MotionEvent;

/** Injects one display-targeted mouse operation from the Shizuku shell. */
public final class DesktopPointerCommand {
    private DesktopPointerCommand() {
    }

    public static void main(final String[] args) {
        try {
            if (args.length == 4 && "hover".equals(args[0])) {
                movePointer(
                        positiveInt(args[1], "display id"),
                        point(args[2], args[3]));
                System.out.println("pointer-hovered");
                return;
            }
            if (args.length == 4 && "click".equals(args[0])) {
                final int displayId = positiveInt(args[1], "display id");
                movePointer(displayId, point(args[2], args[3]));
                DesktopPointerInjector.injectClick(
                        displayId, MotionEvent.BUTTON_PRIMARY);
                System.out.println("pointer-clicked");
                return;
            }
            if (args.length == 7 && "drag".equals(args[0])) {
                DesktopPointerInjector.injectMouseDrag(
                        positiveInt(args[1], "display id"),
                        point(args[2], args[3]),
                        point(args[4], args[5]),
                        nonNegativeLong(args[6], "duration"));
                System.out.println("pointer-dragged");
                return;
            }
            System.err.println("usage: DesktopPointerCommand "
                    + "<hover display x y|click display x y|"
                    + "drag display start-x start-y "
                    + "end-x end-y duration-ms>");
            System.exit(64);
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("pointer command failed: " + error);
            System.exit(1);
        }
    }

    private static void movePointer(
            final int displayId,
            final Point position) throws ReflectiveOperationException {
        NubiaMouseController.createOrUpdateViewport();
        NubiaMouseController.setMousePosition(displayId, position);
        DesktopPointerInjector.injectMouseHover(displayId, position);
    }

    private static Point point(final String x, final String y) {
        return new Point(nonNegativeInt(x, "x"), nonNegativeInt(y, "y"));
    }

    private static int positiveInt(final String value, final String label) {
        final int parsed = nonNegativeInt(value, label);
        if (parsed == 0) {
            throw new IllegalArgumentException("invalid " + label);
        }
        return parsed;
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
