package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopUiFactory.COLOR_TEXT;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
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
        panel.setPadding(dp(12), dp(4), dp(12), dp(12));
        panel.setBackground(mUi.menuSurface());
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
        final DesktopPanelWindowController panels = mActivity.panels();
        if (panels == null || mPanel == null) {
            return;
        }
        if (panels.isRequested(mPanel)) {
            mActivity.hideAllPanels();
            return;
        }
        mActivity.captureInteractionStackForPanel();
        render();

        final int areaWidth = mActivity.getDesktopAreaWidth();
        final int areaHeight = mActivity.getDesktopAreaHeight();
        final int width = mUi.menuWidth(areaWidth, dp(8));
        final int maxHeight = Math.max(1,
                areaHeight - mActivity.getTaskbarHeight() - dp(16));
        mPanel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST));
        final int height = Math.min(maxHeight, mPanel.getMeasuredHeight());
        final int left = mActivity.getDesktopAreaLeft()
                + Math.max(0, areaWidth - width - dp(8));
        final int top = mActivity.getDesktopAreaTop() + Math.max(dp(8),
                areaHeight - mActivity.getTaskbarHeight() - dp(8) - height);
        if (!panels.show(
                mPanel,
                left,
                top,
                width,
                height,
                false,
                mActivity.getString(R.string.section_quick_controls))) {
            mActivity.setErrorStatus(
                    "PANEL-001",
                    mActivity.getString(
                            R.string.status_desktop_panel_unavailable));
        }
    }

    private void render() {
        mPanel.removeAllViews();

        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(mActivity);
        title.setText(R.string.section_quick_controls);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final ImageButton settings = mUi.menuIconButton(
                R.drawable.ic_settings, R.string.action_settings);
        settings.setOnClickListener(view -> mActivity.openSettings());
        final LinearLayout.LayoutParams settingsParams =
                new LinearLayout.LayoutParams(
                        dp(48), dp(48));
        header.addView(settings, settingsParams);

        final ImageButton close = mUi.menuIconButton(
                R.drawable.ic_close, R.string.action_close);
        close.setOnClickListener(view -> mActivity.hideAllPanels());
        header.addView(close, new LinearLayout.LayoutParams(
                dp(48), dp(48)));
        mActivity.registerAutomationUiElement(settings,
                "quick_controls.settings", "button", mActivity.getString(R.string.action_settings));
        mActivity.registerAutomationUiElement(close,
                "quick_controls.close", "button", mActivity.getString(R.string.action_close));
        mPanel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(4), 0, 0);
        mActivity.populateSystemControls(content, dp(10));

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(false);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        mPanel.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
