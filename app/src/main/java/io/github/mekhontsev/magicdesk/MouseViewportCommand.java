package io.github.mekhontsev.magicdesk;

import java.lang.reflect.InvocationTargetException;

/** Requests the vendor input stack to rebuild its mouse viewport. */
public final class MouseViewportCommand {
    private MouseViewportCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 0) {
            System.err.println("usage: MouseViewportCommand");
            System.exit(64);
            return;
        }

        try {
            NubiaMouseController.createOrUpdateViewport();
            System.out.println("mouse-viewport=updated");
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("mouse viewport update failed: " + cause);
            System.exit(1);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("mouse viewport update failed: " + e);
            System.exit(1);
        }
    }
}
