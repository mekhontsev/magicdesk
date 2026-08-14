package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Typeface;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

final class SettingsView {
    interface Actions {
        void setTaskbarAutoHide(boolean enabled);

        void setKeepDesktopAwake(boolean enabled);

        void setOpenTouchpadAutomatically(boolean enabled);

        void setOpenFilesWithSingleClick(boolean enabled);

        void openDeviceSetup();

        void openDiagnostics();

        void showAbout();

        void closeSettings();
    }

    private final Activity mActivity;
    private final DesktopUiFactory mUi;
    private final Actions mActions;
    private Switch mTaskbarAutoHide;
    private Switch mKeepDesktopAwake;
    private Switch mOpenTouchpadAutomatically;
    private Switch mOpenFilesWithSingleClick;
    private boolean mRendering;

    SettingsView(final Activity activity, final Actions actions) {
        mActivity = activity;
        mUi = new DesktopUiFactory(activity);
        mActions = actions;
    }

    View create() {
        final LinearLayout page = new LinearLayout(mActivity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(DesktopUiFactory.COLOR_BACKGROUND);
        final Display display = mActivity.getDisplay();
        final int displayId = display == null
                ? Display.DEFAULT_DISPLAY : display.getDisplayId();
        final int bottomPadding = dp(18)
                + (displayId == Display.DEFAULT_DISPLAY
                        ? 0 : dp(DesktopShellActivity.TASKBAR_HEIGHT_DP));
        page.setPadding(dp(20), dp(18), dp(20), bottomPadding);
        SystemBarInsets.addToPadding(page);

        page.addView(createHeader(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        addSection(content, R.string.settings_section_desktop, 20);
        mTaskbarAutoHide = addSwitch(
                content, R.string.settings_taskbar_auto_hide);
        mTaskbarAutoHide.setOnCheckedChangeListener((button, checked) -> {
            if (!mRendering) {
                mActions.setTaskbarAutoHide(checked);
            }
        });
        mOpenFilesWithSingleClick = addSwitch(
                content, R.string.settings_open_files_single_click);
        mOpenFilesWithSingleClick.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (!mRendering) {
                        mActions.setOpenFilesWithSingleClick(checked);
                    }
                });

        addSection(content, R.string.settings_section_session, 22);
        mOpenTouchpadAutomatically = addSwitch(
                content, R.string.settings_open_touchpad_automatically);
        mOpenTouchpadAutomatically.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (!mRendering) {
                        mActions.setOpenTouchpadAutomatically(checked);
                    }
                });
        mKeepDesktopAwake = addSwitch(
                content, R.string.settings_keep_desktop_awake);
        mKeepDesktopAwake.setOnCheckedChangeListener((button, checked) -> {
            if (!mRendering) {
                mActions.setKeepDesktopAwake(checked);
            }
        });

        addSection(content, R.string.settings_section_support, 22);
        final GridLayout support = new GridLayout(mActivity);
        support.setColumnCount(3);
        addAction(support, R.string.action_device_setup,
                mActions::openDeviceSetup);
        addAction(support, R.string.action_diagnostics,
                mActions::openDiagnostics);
        addAction(support, R.string.action_about,
                mActions::showAbout);
        content.addView(support, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        scrollParams.setMargins(0, dp(18), 0, 0);
        page.addView(scroll, scrollParams);
        return page;
    }

    void render(final MagicDeskSettings.Values settings) {
        if (settings == null || mTaskbarAutoHide == null
                || mKeepDesktopAwake == null
                || mOpenTouchpadAutomatically == null
                || mOpenFilesWithSingleClick == null) {
            return;
        }
        mRendering = true;
        mTaskbarAutoHide.setChecked(settings.taskbarAutoHide);
        mOpenFilesWithSingleClick.setChecked(
                settings.openFilesWithSingleClick);
        mOpenTouchpadAutomatically.setChecked(
                settings.openTouchpadAutomatically);
        mKeepDesktopAwake.setChecked(settings.keepDesktopAwake);
        mRendering = false;
    }

    private View createHeader() {
        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(mActivity);
        title.setText(R.string.settings_title);
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final ImageButton close = new ImageButton(mActivity);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setContentDescription(
                mActivity.getString(R.string.action_close));
        close.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                dp(6),
                DesktopUiFactory.COLOR_PANEL_ALT));
        close.setOnClickListener(view -> mActions.closeSettings());
        header.addView(close, new LinearLayout.LayoutParams(dp(46), dp(46)));
        return header;
    }

    private void addSection(
            final LinearLayout parent,
            final int titleResId,
            final int topMargin) {
        final TextView title = mUi.sectionTitle(titleResId);
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(topMargin), 0, dp(6));
        parent.addView(title, params);
    }

    private Switch addSwitch(
            final LinearLayout parent,
            final int labelResId) {
        final Switch toggle = new Switch(mActivity);
        toggle.setText(labelResId);
        toggle.setTextColor(DesktopUiFactory.COLOR_TEXT);
        toggle.setTextSize(15);
        toggle.setGravity(Gravity.CENTER_VERTICAL);
        toggle.setPadding(dp(4), 0, dp(4), 0);
        parent.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));
        return toggle;
    }

    private void addAction(
            final GridLayout parent,
            final int labelResId,
            final Runnable action) {
        final Button button = mUi.actionButton(
                labelResId, DesktopUiFactory.COLOR_PANEL_ALT);
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setOnClickListener(view -> action.run());
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(52);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        parent.addView(button, params);
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
