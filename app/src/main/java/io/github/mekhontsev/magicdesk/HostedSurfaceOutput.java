package io.github.mekhontsev.magicdesk;

import android.view.Surface;
import io.github.mekhontsev.magicdesk.hosted.HostedTextState;
import io.github.mekhontsev.magicdesk.hosted.HostedTouchPhase;

/** A borrowed renderer output. Input positions are normalized to its content, not the View. */
interface HostedSurfaceOutput extends AutoCloseable {
    enum Button { PRIMARY, MIDDLE, SECONDARY, BACK, FORWARD }

    void setSurface(Surface surface, int width, int height);
    void focus();
    default void blur() { }
    void pointer(float x, float y);
    /** UI-thread callback: nonzero request identity, zero revokes relative pointer capture. */
    default void captureListener(java.util.function.LongConsumer listener) { }
    default void pointerCapture(long serial, boolean captured) { }
    default void relative(long serial, float dx, float dy) { }
    void button(float x, float y, Button button, boolean down);
    void scroll(float x, float y, float horizontal, float vertical);
    default boolean supportsTouch() { return false; }
    default boolean supportsTablet() { return false; }
    default void touch(int contact, HostedTouchPhase phase, float x, float y, float pressure) { }
    /** Normalized pressure; tilt in radians. Buttons: tip=1, primary barrel=2, secondary barrel=4. */
    default void tablet(boolean eraser, boolean proximity, float x, float y, float pressure,
            float tiltX, float tiltY, int buttons) { }
    default void cancelContacts() { }
    void key(int androidKey, int scanCode, boolean down);
    void text(HostedTextState editor, String text);
    /** True when the client owns preedit display; otherwise Android retains composition until commit. */
    default boolean preedit(HostedTextState editor, String text, int cursor) { return false; }
    default boolean supportsText() { return true; }
    default HostedTextState textState() { return null; }
    default boolean deleteText(HostedTextState snapshot, int before, int after, boolean codePoints, String preedit, int cursor) { return false; }
    @Override void close();
}
