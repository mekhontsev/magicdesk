package io.github.mekhontsev.magicdesk;

import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

final class DesktopItemViewFactory {
    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;

    DesktopItemViewFactory(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    View app(final AppItem app, final boolean workspaceApp) {
        final LinearLayout item = iconContainer();
        if (workspaceApp) {
            item.setBackground(mUi.rounded(
                    0x55172033,
                    dp(8),
                    DesktopUiFactory.COLOR_AMBER));
        }
        final ImageView icon = new ImageView(mActivity);
        icon.setImageDrawable(app.icon);
        item.addView(icon, iconParams());
        addLabel(item, app.label);
        return item;
    }

    View file(final DesktopFile file, final boolean selected) {
        final LinearLayout item = iconContainer();
        if (selected) {
            item.setBackground(mUi.rounded(
                    0x661F2C3A,
                    dp(8),
                    DesktopUiFactory.COLOR_CYAN));
        }
        final ImageView icon = new ImageView(mActivity);
        icon.setScaleType(file.thumbnail == null
                ? ImageView.ScaleType.CENTER_INSIDE
                : ImageView.ScaleType.CENTER_CROP);
        if (file.folderShortcut != null) {
            icon.setImageResource(R.drawable.ic_desktop_folder_link);
            if (!file.folderShortcut.available) {
                icon.setAlpha(0.55f);
                item.setAlpha(0.72f);
            }
        } else if (file.thumbnail != null) {
            icon.setImageBitmap(file.thumbnail);
            icon.setBackground(mUi.rounded(
                    0x66111827,
                    dp(6),
                    0x99E5E7EB));
            icon.setClipToOutline(true);
            icon.setPadding(dp(1), dp(1), dp(1), dp(1));
        } else {
            icon.setImageResource(FileIconResolver.forFile(
                    file.directory, file.mimeType));
        }
        icon.setContentDescription(file.displayName());
        item.addView(icon, iconParams());
        addLabel(item, file.displayName());
        return item;
    }

    View overflow(final int hiddenCount) {
        final LinearLayout item = iconContainer();
        final ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_desktop_folder);
        item.addView(icon, iconParams());
        addLabel(item, mActivity.getString(
                R.string.desktop_more_files, Integer.valueOf(hiddenCount)));
        return item;
    }

    private LinearLayout iconContainer() {
        final LinearLayout item = new LinearLayout(mActivity);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        item.setPadding(
                desktopDp(8, 5),
                desktopDp(6, 4),
                desktopDp(8, 5),
                desktopDp(6, 4));
        item.setClickable(true);
        item.setFocusable(true);
        return item;
    }

    private LinearLayout.LayoutParams iconParams() {
        return new LinearLayout.LayoutParams(
                desktopDp(44, 34), desktopDp(44, 34));
    }

    private void addLabel(
            final LinearLayout item,
            final CharSequence text) {
        final TextView label = new TextView(mActivity);
        label.setText(text);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(mActivity.isCompactDesktopPreview() ? 10 : 12);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setShadowLayer(dp(2), 0, dp(1), 0xE6000000);
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, 0);
        item.addView(label, params);
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }

    private int desktopDp(
            final int normalValue,
            final int compactValue) {
        return mUi.desktopDp(
                normalValue,
                compactValue,
                mActivity.isCompactDesktopPreview());
    }
}
