package io.github.mekhontsev.magicdesk;

/** X11's pointer buttons and eight-bit keycode boundary. */
final class X11InputEncoding {
    private X11InputEncoding() { }

    static int button(HostedSurfaceOutput.Button button) {
        return switch (button) {
            case PRIMARY -> 1;
            case MIDDLE -> 2;
            case SECONDARY -> 3;
            case BACK -> 8;
            case FORWARD -> 9;
        };
    }

    static int scanCode(int scan) { return scan < 0 || scan > 247 ? 0 : scan; }

}
