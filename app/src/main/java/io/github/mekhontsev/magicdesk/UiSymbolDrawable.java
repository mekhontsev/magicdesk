package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.BitmapDrawable;

/** Retains drawable identity while symbolic resources change. No resolution occurs in draw(). */
final class UiSymbolDrawable extends Drawable implements Drawable.Callback {
    private final Resources mResources;
    private final AppearanceScopeSource mSource;
    private final int mOriginal;
    private final UiColor mRole;
    private final int mWidth, mHeight;
    private Drawable mDrawable;
    private Bitmap mAsset;
    private int mResolved, mAlpha = 255;
    private ColorFilter mFilter;
    private ColorStateList mTint;
    UiSymbolDrawable(Context context, int original, UiColor role) {
        mResources = context.getResources(); mSource = new AppearanceScopeSource(context);
        mOriginal = original; mRole = role;
        Drawable originalDrawable = mResources.getDrawable(original, null);
        mWidth = originalDrawable.getIntrinsicWidth(); mHeight = originalDrawable.getIntrinsicHeight();
        refresh();
    }
    void refresh() {
        var appearance = mSource.resolve();
        var theme = appearance.theme();
        int resource = ShellIconResources.resolve(mOriginal, theme.resources());
        ShellResources.Icon role = ShellIconResources.role(mOriginal);
        String path = role == null ? null : theme.resources().iconAssets().get(role);
        Bitmap asset = path == null ? null : appearance.assets().icon(path);
        if (resource != mResolved || asset != mAsset) {
            if (mDrawable != null) mDrawable.setCallback(null);
            mDrawable = asset == null ? mResources.getDrawable(resource, null).mutate()
                    : new BitmapDrawable(mResources, asset);
            mResolved = resource; mAsset = asset;
            mDrawable.setCallback(this); mDrawable.setBounds(getBounds());
            mDrawable.setAlpha(mAlpha); mDrawable.setColorFilter(mFilter);
            mDrawable.setState(getState());
        }
        mDrawable.setTintList(mTint != null ? mTint : asset == null ? UiAppearance.states(theme, mRole) : null);
        invalidateSelf();
    }
    @Override public boolean isStateful() { return mDrawable != null && mDrawable.isStateful(); }
    @Override protected boolean onStateChange(int[] state) {
        return mDrawable != null && mDrawable.setState(state);
    }
    @Override public void setTintList(ColorStateList tint) {
        mTint = tint;
        refresh();
    }
    @Override public void draw(Canvas canvas) { mDrawable.draw(canvas); }
    @Override protected void onBoundsChange(Rect bounds) { if (mDrawable != null) mDrawable.setBounds(bounds); }
    @Override public void setAlpha(int alpha) { mAlpha = alpha; mDrawable.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { mFilter = filter; mDrawable.setColorFilter(filter); }
    @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return mWidth; }
    @Override public int getIntrinsicHeight() { return mHeight; }
    @Override public void invalidateDrawable(Drawable drawable) { invalidateSelf(); }
    @Override public void scheduleDrawable(Drawable drawable, Runnable what, long when) { scheduleSelf(what, when); }
    @Override public void unscheduleDrawable(Drawable drawable, Runnable what) { unscheduleSelf(what); }
}
