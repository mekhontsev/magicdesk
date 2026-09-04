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

        void setDisableAdaptiveBrightnessOnExternalDesktop(boolean enabled);

        void setOpenTouchpadAutomatically(boolean enabled);

        void setOpenFilesWithSingleClick(boolean enabled);

        void setMcpEnabled(boolean enabled);

        void setMcpDeveloperTools(boolean enabled);

        void setMcpShellTools(boolean enabled);

        void copyMcpConnection();

        void regenerateMcpToken();

        void configureTermuxX11();

        void openDeviceSetup();

        void openApplicationSettings();

        void openDiagnostics();

        void showAbout();
    }

    private static final int CONTENT_MAX_WIDTH_DP = 540;

    private final Activity mActivity;
    private final DesktopUiFactory mUi;
    private final Actions mActions;
    private Switch mTaskbarAutoHide;
    private Switch mKeepDesktopAwake;
    private Switch mDisableAdaptiveBrightness;
    private Switch mOpenTouchpadAutomatically;
    private Switch mOpenFilesWithSingleClick;
    private Switch mMcpEnabled;
    private Switch mMcpDeveloperTools;
    private Switch mMcpShellTools;
    private TextView mMcpStatus;
    private boolean mRendering;

    SettingsView(final Activity activity, final Actions actions) {
        mActivity = activity;
        mUi = new DesktopUiFactory(activity);
        mActions = actions;
    }

    View create() {
        final LinearLayout page = new LinearLayout(mActivity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(DesktopUiFactory.COLOR_PANEL);
        page.setPadding(dp(14), dp(10), dp(14), dp(14));
        SystemBarInsets.addToPadding(page);

        final LinearLayout content = new LinearLayout(mActivity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(createHeader(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        final View headerDivider = new View(mActivity);
        headerDivider.setBackgroundColor(DesktopUiFactory.COLOR_CYAN);
        content.addView(headerDivider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        addSection(content, R.string.settings_section_desktop, 14);
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
        addAction(
                content,
                android.R.drawable.ic_menu_manage,
                R.string.app_presentation_profiles_title,
                mActions::openApplicationSettings);

        addSection(content, R.string.settings_section_session, 14);
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
        mDisableAdaptiveBrightness = addSwitch(
                content,
                R.string.settings_disable_adaptive_brightness);
        mDisableAdaptiveBrightness.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (!mRendering) {
                        mActions.setDisableAdaptiveBrightnessOnExternalDesktop(
                                checked);
                    }
                });

        addSection(content, R.string.settings_section_automation, 14);
        mMcpEnabled = addSwitch(content, R.string.settings_mcp_enabled);
        mMcpEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (!mRendering) {
                mActions.setMcpEnabled(checked);
            }
        });
        mMcpDeveloperTools = addSwitch(
                content, R.string.settings_mcp_developer_tools);
        mMcpDeveloperTools.setOnCheckedChangeListener((button, checked) -> {
            if (!mRendering) {
                mActions.setMcpDeveloperTools(checked);
            }
        });
        mMcpShellTools = addSwitch(
                content, R.string.settings_mcp_shell_tools);
        mMcpShellTools.setOnCheckedChangeListener((button, checked) -> {
            if (!mRendering) {
                mActions.setMcpShellTools(checked);
            }
        });
        mMcpStatus = new TextView(mActivity);
        mMcpStatus.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mMcpStatus.setTextSize(12);
        mMcpStatus.setPadding(dp(8), dp(7), dp(8), dp(7));
        content.addView(mMcpStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        addAction(
                content,
                android.R.drawable.ic_menu_set_as,
                R.string.settings_mcp_connection,
                mActions::copyMcpConnection);
        addAction(
                content,
                android.R.drawable.ic_popup_sync,
                R.string.settings_mcp_regenerate_token,
                mActions::regenerateMcpToken);

        if (TermuxX11Integration.isAvailable(mActivity)) {
            addSection(content, R.string.settings_section_integrations, 14);
            addAction(
                    content,
                    android.R.drawable.ic_menu_edit,
                    R.string.settings_termux_x11_command,
                    mActions::configureTermuxX11);
        }

        addSection(content, R.string.settings_section_support, 14);
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

    void render(
            final MagicDeskSettings.Values settings,
            final MagicDeskMcpPreferences.Values mcp,
            final MagicDeskMcpRuntime.Snapshot runtime) {
        if (settings == null || mTaskbarAutoHide == null
                || mKeepDesktopAwake == null
                || mDisableAdaptiveBrightness == null
                || mOpenTouchpadAutomatically == null
                || mOpenFilesWithSingleClick == null
                || mcp == null || runtime == null
                || mMcpEnabled == null || mMcpDeveloperTools == null
                || mMcpShellTools == null
                || mMcpStatus == null) {
            return;
        }
        mRendering = true;
        mTaskbarAutoHide.setChecked(settings.taskbarAutoHide);
        mOpenFilesWithSingleClick.setChecked(
                settings.openFilesWithSingleClick);
        mOpenTouchpadAutomatically.setChecked(
                settings.openTouchpadAutomatically);
        mKeepDesktopAwake.setChecked(settings.keepDesktopAwake);
        mDisableAdaptiveBrightness.setChecked(
                settings.disableAdaptiveBrightnessOnExternalDesktop);
        mMcpEnabled.setChecked(mcp.enabled);
        mMcpDeveloperTools.setChecked(mcp.developerTools);
        mMcpDeveloperTools.setEnabled(mcp.enabled);
        mMcpDeveloperTools.setAlpha(mcp.enabled ? 1f : 0.5f);
        mMcpShellTools.setChecked(mcp.shellTools);
        mMcpShellTools.setEnabled(mcp.enabled);
        mMcpShellTools.setAlpha(mcp.enabled ? 1f : 0.5f);
        final int status = runtime.running
                ? R.string.settings_mcp_status_running
                : mcp.enabled
                        ? R.string.settings_mcp_status_waiting
                        : R.string.settings_mcp_status_disabled;
        mMcpStatus.setText(mActivity.getString(status, mcp.endpoint()));
        mRendering = false;
    }

    private View createHeader() {
        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(46));

        final ImageView icon = new ImageView(mActivity);
        icon.setImageResource(android.R.drawable.ic_menu_preferences);
        icon.setColorFilter(DesktopUiFactory.COLOR_CYAN);
        icon.setContentDescription(null);
        header.addView(icon, new LinearLayout.LayoutParams(dp(24), dp(24)));

        final TextView title = new TextView(mActivity);
        title.setText(R.string.settings_title);
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMargins(dp(10), 0, 0, 0);
        header.addView(title, titleParams);
        return header;
    }

    private void addSection(
            final LinearLayout parent,
            final int titleResId,
            final int topMargin) {
        final TextView title = mUi.sectionTitle(titleResId);
        title.setTextSize(16);
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(8), dp(topMargin), dp(8), dp(5));
        parent.addView(title, params);
    }

    private Switch addSwitch(
            final LinearLayout parent,
            final int labelResId) {
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), 0, dp(8), 0);
        row.setMinimumHeight(dp(52));
        applyPressedBackground(row);

        final TextView label = new TextView(mActivity);
        label.setText(labelResId);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(14);
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
        row.setOnClickListener(view -> {
            if (toggle.isEnabled()) {
                toggle.toggle();
            }
        });
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
        row.setPadding(dp(8), 0, dp(8), 0);
        row.setMinimumHeight(dp(50));
        row.setClickable(true);
        row.setFocusable(true);
        applyPressedBackground(row);
        row.setOnClickListener(view -> action.run());

        final ImageView icon = new ImageView(mActivity);
        icon.setImageResource(iconResId);
        icon.setColorFilter(DesktopUiFactory.COLOR_CYAN);
        icon.setContentDescription(null);
        row.addView(icon, new LinearLayout.LayoutParams(dp(22), dp(22)));

        final TextView label = new TextView(mActivity);
        label.setText(labelResId);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(14);
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

    private void applyPressedBackground(final View view) {
        view.setBackgroundColor(Color.TRANSPARENT);
        view.setBackgroundTintList(new ColorStateList(
                new int[][] {
                    new int[] {android.R.attr.state_pressed},
                    new int[0]
                },
                new int[] {
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    Color.TRANSPARENT
                }));
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
