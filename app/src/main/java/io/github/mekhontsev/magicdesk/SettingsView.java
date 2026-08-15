package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    }

    private static final int CONTENT_MAX_WIDTH_DP = 680;

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
        page.setPadding(dp(16), dp(12), dp(16), dp(18));
        SystemBarInsets.addToPadding(page);

        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(createHeader(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addSection(content, R.string.settings_section_desktop, 18);
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

        addSection(content, R.string.settings_section_session, 18);
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

        addSection(content, R.string.settings_section_support, 18);
        addAction(content,
                android.R.drawable.ic_menu_manage,
                R.string.action_device_setup,
                mActions::openDeviceSetup);
        addAction(content,
                android.R.drawable.ic_menu_info_details,
                R.string.action_diagnostics,
                mActions::openDiagnostics);
        addAction(content,
                android.R.drawable.ic_menu_help,
                R.string.action_about,
                mActions::showAbout);

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        final FrameLayout contentHost = new FrameLayout(mActivity);
        final int availableWidthDp = Math.max(
                1,
                mActivity.getResources().getConfiguration().screenWidthDp
                        - 32);
        final FrameLayout.LayoutParams contentParams =
                new FrameLayout.LayoutParams(
                        dp(Math.min(CONTENT_MAX_WIDTH_DP, availableWidthDp)),
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        contentHost.addView(content, contentParams);
        scroll.addView(contentHost, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
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
        final TextView title = new TextView(mActivity);
        title.setText(R.string.settings_title);
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setMinHeight(dp(48));
        return title;
    }

    private void addSection(
            final LinearLayout parent,
            final int titleResId,
            final int topMargin) {
        final TextView title = mUi.sectionTitle(titleResId);
        title.setTextSize(15);
        title.setTextColor(DesktopUiFactory.COLOR_CYAN);
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(4), dp(topMargin), dp(4), dp(6));
        parent.addView(title, params);
    }

    private Switch addSwitch(
            final LinearLayout parent,
            final int labelResId) {
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, dp(4), 0);
        row.setMinimumHeight(dp(58));

        final TextView label = new TextView(mActivity);
        label.setText(labelResId);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(15);
        label.setMaxLines(2);
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final Switch toggle = new Switch(mActivity);
        toggle.setShowText(false);
        toggle.setContentDescription(mActivity.getString(labelResId));
        final LinearLayout.LayoutParams toggleParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        toggleParams.setMargins(dp(16), 0, 0, 0);
        row.addView(toggle, toggleParams);
        row.setOnClickListener(view -> toggle.toggle());
        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        addDivider(parent);
        return toggle;
    }

    private void addAction(
            final LinearLayout parent,
            final int iconResId,
            final int labelResId,
            final Runnable action) {
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), 0, dp(4), 0);
        row.setMinimumHeight(dp(56));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackgroundColor(Color.TRANSPARENT);
        row.setBackgroundTintList(new ColorStateList(
                new int[][] {
                    new int[] {android.R.attr.state_pressed},
                    new int[0]
                },
                new int[] {
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    Color.TRANSPARENT
                }));
        row.setOnClickListener(view -> action.run());

        final ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconResId);
        icon.setColorFilter(DesktopUiFactory.COLOR_MUTED);
        icon.setContentDescription(null);
        row.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        final TextView label = new TextView(mActivity);
        label.setText(labelResId);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(15);
        final LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        labelParams.setMargins(dp(14), 0, dp(12), 0);
        row.addView(label, labelParams);

        final ImageView arrow = new ImageView(mActivity);
        arrow.setImageResource(android.R.drawable.ic_media_next);
        arrow.setColorFilter(DesktopUiFactory.COLOR_MUTED);
        arrow.setContentDescription(null);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(18), dp(18)));

        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        addDivider(parent);
    }

    private void addDivider(final LinearLayout parent) {
        final View divider = new View(mActivity);
        divider.setBackgroundColor(DesktopUiFactory.COLOR_PANEL_ALT);
        parent.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
