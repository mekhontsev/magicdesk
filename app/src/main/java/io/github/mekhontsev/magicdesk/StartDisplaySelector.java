package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.Toast;

/** A Start-local destination choice, not an input or Desktop session selection. */
final class StartDisplaySelector {
    record Target(int displayId, String uniqueId) { }

    private final Activity mActivity;
    private final Button mButton;
    private DesktopDisplayInfo mSelected;
    private PopupMenu mMenu;
    private int mGeneration;

    StartDisplaySelector(Activity activity, DesktopUiFactory ui) {
        mActivity = activity;
        mButton = ui.actionButton(R.string.start_current_display, DesktopUiFactory.COLOR_PANEL_ALT);
        mButton.setTextSize(13);
        mButton.setSingleLine(true);
        mButton.setEllipsize(TextUtils.TruncateAt.END);
        mButton.setMinWidth(0);
        mButton.setMinimumWidth(0);
        mButton.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        final var icon = activity.getDrawable(R.drawable.ic_arrow_down);
        if (icon != null) {
            icon.setTint(DesktopUiFactory.COLOR_TEXT);
            icon.setBounds(0, 0, ui.dp(16), ui.dp(16));
            mButton.setCompoundDrawablesRelative(null, null, icon, null);
            mButton.setCompoundDrawablePadding(ui.dp(4));
        }
        updateLabel();
        mButton.setOnClickListener(view -> show());
    }

    Button view() { return mButton; }

    Target target() {
        return mSelected == null
                ? new Target(mActivity.getDisplay().getDisplayId(), null)
                : new Target(mSelected.id, mSelected.uniqueId);
    }

    void dismiss() {
        ++mGeneration;
        if (mMenu != null) { mMenu.dismiss(); mMenu = null; }
    }

    private void show() {
        dismiss();
        final int generation = mGeneration;
        TaskCommandQueue.execute(() -> {
            try {
                final DesktopDisplayInfo[] displays = DesktopDisplayCatalog.read();
                mActivity.runOnUiThread(() -> {
                    if (generation != mGeneration || !mButton.isAttachedToWindow()
                            || !mButton.isShown() || mActivity.isDestroyed()) { return; }
                    final PopupMenu menu = new PopupMenu(mActivity, mButton, Gravity.END);
                    mMenu = menu;
                    menu.getMenu().add(0, 0, 0, R.string.start_current_display)
                            .setCheckable(true).setChecked(mSelected == null)
                            .setOnMenuItemClickListener(item -> { select(null); return true; });
                    int index = 1;
                    for (final DesktopDisplayInfo display : displays) {
                        if (display.id == mActivity.getDisplay().getDisplayId()
                                || "unknown".equals(display.source)) { continue; }
                        menu.getMenu().add(0, index, index++, display.name)
                                .setCheckable(true)
                                .setChecked(mSelected != null && mSelected.id == display.id
                                        && mSelected.uniqueId.equals(display.uniqueId))
                                .setOnMenuItemClickListener(item -> { select(display); return true; });
                    }
                    menu.show();
                });
            } catch (java.io.IOException | RuntimeException error) {
                mActivity.runOnUiThread(() -> {
                    if (generation == mGeneration && mButton.isShown()) {
                        Toast.makeText(mActivity, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void select(DesktopDisplayInfo display) {
        mSelected = display;
        updateLabel();
    }

    private void updateLabel() {
        final String label = mSelected == null
                ? mActivity.getString(R.string.start_current_display) : mSelected.name;
        mButton.setText(label);
        final String description = mActivity.getString(R.string.start_launch_display, label);
        mButton.setContentDescription(description);
        mButton.setTooltipText(description);
    }
}
