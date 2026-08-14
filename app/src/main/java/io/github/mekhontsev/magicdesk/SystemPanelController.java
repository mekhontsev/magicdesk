package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_CYAN;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_PANEL_ALT;
import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_TEXT;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class SystemPanelController {
    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;

    private LinearLayout mPanel;

    SystemPanelController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    LinearLayout createPanel() {
        final LinearLayout panel = new LinearLayout(mActivity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(14), dp(14), dp(12));
        panel.setBackground(mUi.rounded(COLOR_PANEL, dp(8), COLOR_CYAN));
        panel.setVisibility(View.GONE);
        panel.setClickable(true);
        panel.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(final View view) {
                        mActivity.setHardwarePanelVisible(true);
                    }

                    @Override
                    public void onViewDetachedFromWindow(final View view) {
                        mActivity.setHardwarePanelVisible(false);
                    }
                });
        mPanel = panel;
        return panel;
    }

    void toggle() {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays == null || mPanel == null) {
            return;
        }
        if (overlays.isVisible(mPanel)) {
            mActivity.hideAllPanels();
            return;
        }
        mActivity.captureInteractionStackForPanel();
        render();

        final int areaWidth = mActivity.getDesktopAreaWidth();
        final int areaHeight = mActivity.getDesktopAreaHeight();
        final int width = Math.min(
                dp(420), Math.max(dp(280), areaWidth - dp(16)));
        final int height = Math.max(
                dp(180),
                areaHeight - mActivity.getTaskbarHeight() - dp(16));
        final int left = mActivity.getDesktopAreaLeft()
                + Math.max(0, areaWidth - width - dp(8));
        final int top = mActivity.getDesktopAreaTop() + dp(8);
        if (!overlays.show(
                mPanel,
                left,
                top,
                width,
                height,
                false,
                "MagicDesk system")) {
            mActivity.setErrorStatus(
                    "OVERLAY-001",
                    mActivity.getString(
                            R.string.status_overlay_panel_unavailable));
        }
    }

    private void render() {
        mPanel.removeAllViews();

        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(mActivity);
        title.setText(R.string.section_system);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final ImageButton settings = new ImageButton(mActivity);
        settings.setImageResource(android.R.drawable.ic_menu_preferences);
        settings.setContentDescription(
                mActivity.getString(R.string.action_settings));
        settings.setBackground(mUi.rounded(
                COLOR_PANEL_ALT, dp(6), COLOR_PANEL_ALT));
        settings.setOnClickListener(view -> mActivity.openSettings());
        final LinearLayout.LayoutParams settingsParams =
                new LinearLayout.LayoutParams(
                        dp(46), dp(46));
        settingsParams.setMargins(0, 0, dp(8), 0);
        header.addView(settings, settingsParams);

        final Button close =
                mUi.smallButton(R.string.action_close, COLOR_PANEL_ALT);
        close.setOnClickListener(view -> mActivity.hideAllPanels());
        header.addView(close, new LinearLayout.LayoutParams(
                dp(86), LinearLayout.LayoutParams.WRAP_CONTENT));
        mPanel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(12), 0, 0);
        mActivity.populateSystemControls(content, dp(10));

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mPanel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
