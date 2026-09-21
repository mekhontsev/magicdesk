package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.PointerIcon;

/** A guest cursor replaces the Android pointer only over the hosted content. */
final class HostedCursor {
    private Bitmap image;
    private int hotspotX, hotspotY;
    private boolean hidden;
    private PointerIcon icon;
    private int width, height;

    void set(Bitmap image, int hotspotX, int hotspotY, boolean hidden) {
        this.image = image;
        this.hotspotX = hotspotX;
        this.hotspotY = hotspotY;
        this.hidden = hidden;
        icon = null;
    }

    PointerIcon icon(Context context, float scale) {
        if (!(scale > 0) || !Float.isFinite(scale)) return null;
        if (hidden) return PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL);
        if (image == null) return null;
        scale = Math.min(scale, 512f / Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, Math.round(image.getWidth() * scale));
        int h = Math.max(1, Math.round(image.getHeight() * scale));
        if (icon == null || width != w || height != h) {
            Bitmap scaled = Bitmap.createScaledBitmap(image, w, h, true);
            icon = PointerIcon.create(scaled, hotspotX * (w / (float) image.getWidth()),
                    hotspotY * (h / (float) image.getHeight()));
            width = w;
            height = h;
        }
        return icon;
    }
}
