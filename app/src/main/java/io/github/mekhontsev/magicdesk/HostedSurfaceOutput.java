package io.github.mekhontsev.magicdesk;

import android.view.Surface;

/** A borrowed renderer output. Input positions are normalized to its content, not the View. */
interface HostedSurfaceOutput extends AutoCloseable {
    enum Button { PRIMARY, MIDDLE, SECONDARY }

    void setSurface(Surface surface, int width, int height);
    void focus();
    void pointer(float x, float y);
    void button(float x, float y, Button button, boolean down);
    void scroll(float x, float y, float horizontal, float vertical);
    void key(int androidKey, int scanCode, boolean down);
    void text(String text);
    default boolean supportsText() { return true; }
    @Override void close();
}
