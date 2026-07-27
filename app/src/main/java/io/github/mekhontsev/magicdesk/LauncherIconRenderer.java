package io.github.mekhontsev.magicdesk;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

final class LauncherIconRenderer {
    private static final int ICON_SIZE_DP = 64;
    private static final int MIN_ICON_SIZE_PX = 96;
    private static final int MAX_ICON_SIZE_PX = 192;

    private final Resources mResources;
    private final int mIconSize;

    LauncherIconRenderer(final Resources resources) {
        mResources = resources;
        final int densitySize = Math.round(
                ICON_SIZE_DP * resources.getDisplayMetrics().density);
        mIconSize = Math.max(
                MIN_ICON_SIZE_PX,
                Math.min(MAX_ICON_SIZE_PX, densitySize));
    }

    Drawable render(final Drawable source) {
        if (source == null) {
            return null;
        }
        final Bitmap bitmap = Bitmap.createBitmap(
                mIconSize,
                mIconSize,
                Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(bitmap);
        final Rect previousBounds = source.copyBounds();
        final int intrinsicWidth = source.getIntrinsicWidth();
        final int intrinsicHeight = source.getIntrinsicHeight();
        final int width;
        final int height;
        if (intrinsicWidth > 0 && intrinsicHeight > 0) {
            final float scale = Math.min(
                    (float) mIconSize / intrinsicWidth,
                    (float) mIconSize / intrinsicHeight);
            width = Math.max(1, Math.round(intrinsicWidth * scale));
            height = Math.max(1, Math.round(intrinsicHeight * scale));
        } else {
            width = mIconSize;
            height = mIconSize;
        }
        final int left = (mIconSize - width) / 2;
        final int top = (mIconSize - height) / 2;
        source.setBounds(left, top, left + width, top + height);
        try {
            source.draw(canvas);
        } finally {
            source.setBounds(previousBounds);
        }
        return new BitmapDrawable(mResources, bitmap);
    }
}
