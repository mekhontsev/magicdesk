package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.TypedValue;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

/** The runtime notification is a phone control surface, independent of Desktop density. */
final class RuntimeNotificationContent {
    private RuntimeNotificationContent() { }

    static RemoteViews create(final Context context) {
        final DisplayManager displays = context.getSystemService(DisplayManager.class);
        final Display phone = displays == null ? null : displays.getDisplay(Display.DEFAULT_DISPLAY);
        final Context phoneContext = phone == null ? context : context.createDisplayContext(phone);
        final View layout = LayoutInflater.from(phoneContext).inflate(
                R.layout.runtime_notification, new FrameLayout(phoneContext), false);
        final RemoteViews content = new RemoteViews(context.getPackageName(), R.layout.runtime_notification);
        copyDimensions(layout, content);
        return content;
    }

    private static void copyDimensions(final View view, final RemoteViews content) {
        // SystemUI can retain this package's external-display resource density.
        // Resolve the phone panel's dimensions before crossing the RemoteViews boundary.
        final int id = view.getId();
        if (id != View.NO_ID) {
            final ViewGroup.LayoutParams params = view.getLayoutParams();
            if (params != null) {
                if (params.width > 0) {
                    content.setViewLayoutWidth(id, params.width, TypedValue.COMPLEX_UNIT_PX);
                }
                if (params.height > 0) {
                    content.setViewLayoutHeight(id, params.height, TypedValue.COMPLEX_UNIT_PX);
                }
            }
            final boolean rtl = view.getResources().getConfiguration().getLayoutDirection()
                    == View.LAYOUT_DIRECTION_RTL;
            final int start = view.getPaddingStart();
            final int end = view.getPaddingEnd();
            content.setViewPadding(id, rtl ? end : start, view.getPaddingTop(),
                    rtl ? start : end, view.getPaddingBottom());
            if (view instanceof TextView text) {
                content.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_PX, text.getTextSize());
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                copyDimensions(group.getChildAt(i), content);
            }
        }
    }
}
