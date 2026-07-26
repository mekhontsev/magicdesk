package android.view;

import android.os.Looper;

public abstract class InputEventReceiver {
    public InputEventReceiver(final InputChannel inputChannel, final Looper looper) {
    }

    public void onInputEvent(final InputEvent event) {
    }

    public final void finishInputEvent(final InputEvent event, final boolean handled) {
    }

    public void dispose() {
    }
}
