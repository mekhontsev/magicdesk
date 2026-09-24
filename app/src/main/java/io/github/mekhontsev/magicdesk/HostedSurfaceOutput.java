package io.github.mekhontsev.magicdesk;

import android.view.Surface;
import io.github.mekhontsev.magicdesk.hosted.HostedTextState;

/** A borrowed renderer output. Input positions are normalized to its content, not the View. */
interface HostedSurfaceOutput extends AutoCloseable {
    enum Button { PRIMARY, MIDDLE, SECONDARY }

    void setSurface(Surface surface, int width, int height);
    void focus();
    default void blur() { }
    void pointer(float x, float y);
    void button(float x, float y, Button button, boolean down);
    void scroll(float x, float y, float horizontal, float vertical);
    void key(int androidKey, int scanCode, boolean down);
    void text(HostedTextState editor, String text);
    /** True when the client owns preedit display; otherwise Android retains composition until commit. */
    default boolean preedit(HostedTextState editor, String text, int cursor) { return false; }
    default boolean supportsText() { return true; }
    default HostedTextState textState() { return null; }
    default boolean deleteText(HostedTextState snapshot, int before, int after, boolean codePoints, String preedit, int cursor) { return false; }
    @Override void close();
}
