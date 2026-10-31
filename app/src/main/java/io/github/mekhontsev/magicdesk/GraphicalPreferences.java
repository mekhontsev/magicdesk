package io.github.mekhontsev.magicdesk;

import android.content.Context;
import io.github.mekhontsev.magicdesk.hosted.HostedRenderer;

/** Defaults for new graphical sessions, independent of executor and Desktop. */
final class GraphicalPreferences {
    private GraphicalPreferences() { }

    static HostedRenderer renderer(Context context) {
        return HostedRenderer.fromId(context.getSharedPreferences("graphics", Context.MODE_PRIVATE)
                .getString("renderer", HostedRenderer.AUTOMATIC.id));
    }

    static void setRenderer(Context context, HostedRenderer renderer) {
        context.getSharedPreferences("graphics", Context.MODE_PRIVATE).edit()
                .putString("renderer", renderer.id).apply();
    }

    static int rendererLabel(HostedRenderer renderer) {
        return renderer == HostedRenderer.SOFTWARE ? R.string.graphics_renderer_software
                : R.string.graphics_renderer_automatic;
    }
}
