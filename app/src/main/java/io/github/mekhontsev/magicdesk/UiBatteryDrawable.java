package io.github.mekhontsev.magicdesk;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Battery level artwork; its host retains battery observation and semantic tint. */
final class UiBatteryDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ColorStateList tint = ColorStateList.valueOf(0xff808080);
    private int percent = -1, alpha = 255;
    void setPercent(int value) { if (percent != value) { percent = value; invalidateSelf(); } }
    @Override public void draw(Canvas canvas) {
        var b = getBounds();
        if (b.isEmpty()) return;
        int save = canvas.save();
        canvas.translate(b.left, b.top); canvas.scale(b.width() / 28f, b.height() / 16f);
        paint.setColor(tint.getColorForState(getState(), tint.getDefaultColor()));
        paint.setAlpha(Math.round((paint.getColor() >>> 24) * alpha / 255f));
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f);
        canvas.drawRoundRect(1, 1, 24, 15, 2, 2, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawRect(25, 5, 27, 11, paint);
        if (percent > 0) canvas.drawRect(3, 3, 3 + 19 * Math.min(100, percent) / 100f, 13, paint);
        if (percent < 0) canvas.drawRect(10, 7, 15, 9, paint);
        canvas.restoreToCount(save);
    }
    @Override public void setTintList(ColorStateList colors) { tint = colors == null ? ColorStateList.valueOf(0xff808080) : colors; invalidateSelf(); }
    @Override public boolean isStateful() { return tint.isStateful(); }
    @Override protected boolean onStateChange(int[] state) { invalidateSelf(); return true; }
    @Override public void setAlpha(int value) { alpha = value; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
