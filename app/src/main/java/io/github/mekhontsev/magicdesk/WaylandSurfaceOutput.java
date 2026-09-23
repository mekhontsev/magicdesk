package io.github.mekhontsev.magicdesk;

import android.view.Surface;
import io.github.mekhontsev.magicdesk.wayland.WaylandSession;

/** Android gestures enter a Wayland seat through normalized output coordinates. */
final class WaylandSurfaceOutput implements HostedShellOutput {
    private final WaylandSession.Output output;
    WaylandSurfaceOutput(WaylandSession.Output output) { this.output = output; }
    @Override public void setSurface(Surface surface, int width, int height) { output.setSurface(surface, width, height); }
    @Override public java.util.concurrent.CompletableFuture<Void> present(Surface surface, ShellBounds viewport) {
        return output.setSurface(surface, new io.github.mekhontsev.magicdesk.wayland.WaylandViewport(
                viewport.left(), viewport.top(), viewport.width(), viewport.height()));
    }
    @Override public void focus() { output.focus(true); }
    @Override public void pointer(float x, float y) { output.pointer(x, y); }
    @Override public void button(float x, float y, Button button, boolean down) {
        output.pointer(x, y);
        output.button(switch (button) { case PRIMARY -> 0; case MIDDLE -> 1; case SECONDARY -> 2; }, down);
    }
    @Override public void scroll(float x, float y, float horizontal, float vertical) {
        output.pointer(x, y);
        output.scroll(-horizontal * 15, -vertical * 15);
    }
    @Override public void key(int androidKey, int scanCode, boolean down) { output.key(androidKey, scanCode, down); }
    @Override public boolean supportsText() { return false; }
    @Override public void text(String text) { throw new UnsupportedOperationException("Wayland text input is not available"); }
    @Override public void close() { output.close(); }
}
