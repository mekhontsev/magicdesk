package io.github.mekhontsev.magicdesk;

import android.view.Surface;
import android.view.SurfaceControl;

/** The viewer owns a presentation, not the source display or its applications. */
interface DisplayPresentationSurface extends AutoCloseable {
    void attach(Surface surface, SurfaceControl parent);
    @Override void close();
}
