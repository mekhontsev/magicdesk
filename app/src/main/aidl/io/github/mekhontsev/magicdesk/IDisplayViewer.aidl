package io.github.mekhontsev.magicdesk;

import android.view.Surface;
import android.view.SurfaceControl;
import android.view.MotionEvent;
import android.view.KeyEvent;

/** A revocable presentation/input lease, not ownership of the source display. */
interface IDisplayViewer {
    void attach(in Surface surface, in SurfaceControl parent);
    void motion(in MotionEvent event);
    void key(in KeyEvent event);
    void close();
}
