package io.github.mekhontsev.magicdesk;

import android.view.Surface;
import com.termux.x11.X11Session;

final class X11SurfaceOutput implements HostedSurfaceOutput {
    private final X11Session.Output output;

    X11SurfaceOutput(X11Session.Output output) { this.output = output; }

    @Override public void setSurface(Surface surface, int width, int height) { output.setSurface(surface, width, height); }
    @Override public void focus() { output.focus(); }
    @Override public void pointer(float x, float y) { output.pointer(x, y, 0, false); }
    @Override public void button(float x, float y, Button button, boolean down) {
        output.pointer(x, y, X11InputEncoding.button(button), down);
    }
    @Override public void scroll(float x, float y, float horizontal, float vertical) {
        X11InputEncoding.scroll(horizontal, vertical, button -> {
            output.pointer(x, y, button, true);
            output.pointer(x, y, button, false);
        });
    }
    @Override public void key(int androidKey, int scanCode, boolean down) {
        output.key(androidKey, X11InputEncoding.scanCode(scanCode), down);
    }
    @Override public void text(String text) { output.text(text); }
    @Override public void close() { output.close(); }
}
