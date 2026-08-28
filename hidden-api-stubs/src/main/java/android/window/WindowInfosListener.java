package android.window;

import android.util.Pair;
import android.view.InputWindowHandle;

/** Compile-only declaration; the system implementation is used at runtime. */
public abstract class WindowInfosListener {
    public WindowInfosListener() {
    }

    public abstract void onWindowInfosChanged(
            InputWindowHandle[] inputWindowHandles,
            DisplayInfo[] displayInfos);

    public Pair<InputWindowHandle[], DisplayInfo[]> register() {
        throw new UnsupportedOperationException();
    }

    public void unregister() {
        throw new UnsupportedOperationException();
    }

    public static final class DisplayInfo {
    }
}
