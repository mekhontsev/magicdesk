package io.github.mekhontsev.magicdesk.x11;

/** X window-manager intent and confirmed host state, separate from catalog metadata. */
public record X11WindowManagement(boolean managed, Request request, State actual) {
    public record Request(int serial, boolean fullscreen) { }
    public record State(boolean fullscreen) { }

    public X11WindowManagement {
        java.util.Objects.requireNonNull(request);
        java.util.Objects.requireNonNull(actual);
    }

    // JNI constructs the snapshot at the native boundary; callers consume the typed records.
    private X11WindowManagement(boolean managed, int serial, boolean requested, boolean actual) {
        this(managed, new Request(serial, requested), new State(actual));
    }
}
