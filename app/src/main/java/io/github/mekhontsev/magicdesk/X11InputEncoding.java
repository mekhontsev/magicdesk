package io.github.mekhontsev.magicdesk;

import java.util.function.IntConsumer;

/** X11's discrete pointer buttons and eight-bit keycode boundary. */
final class X11InputEncoding {
    private X11InputEncoding() { }

    static int button(HostedSurfaceOutput.Button button) {
        return switch (button) { case PRIMARY -> 1; case MIDDLE -> 2; case SECONDARY -> 3; };
    }

    static int scanCode(int scan) { return scan < 0 || scan > 247 ? 0 : scan; }

    static void scroll(float horizontal, float vertical, IntConsumer click) {
        wheel(vertical, 4, 5, click);
        wheel(horizontal, 7, 6, click);
    }

    private static void wheel(float amount, int positive, int negative, IntConsumer click) {
        int count = Math.min(32, (int) Math.ceil(Math.abs(amount)));
        int button = amount > 0 ? positive : negative;
        for (int i = 0; i < count; i++) click.accept(button);
    }
}
