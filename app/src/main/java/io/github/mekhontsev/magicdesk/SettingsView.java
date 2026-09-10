package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.PopupMenu;
import android.widget.Switch;
import android.widget.TextView;

final class SettingsView {
    interface Actions {
        void setTaskbarAutoHide(boolean enabled);

        void setKeepDesktopAwake(boolean enabled);

        void setDisableAdaptiveBrightnessOnExternalDesktop(boolean enabled);

        void setOpenTouchpadAutomatically(boolean enabled);

        void setCompatibilityOption(DesktopCompatibilityPolicy.Option option, boolean enabled);

        void resetCompatibilityDefaults();

        void setSystemDesktopMode(boolean enabled);

        void setOpenFilesWithSingleClick(boolean enabled);

        void setMcpEnabled(boolean enabled);

        void configureMcpAccess(boolean network);

        void copyMcpConnection();

        void regenerateMcpToken();

        void setMcpNetworkEnabled(boolean enabled);

        void configureMcpNetwork();

        void copyMcpNetworkConnection();

        void regenerateMcpNetworkToken();

        void configureTermuxX11();

        void configureIntegrationPackage(IntegrationPackage integration);

        void configureConsoleFontSize();

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
    private final java.util.EnumMap<DesktopCompatibilityPolicy.Option, Switch> mCompatibility =
            new java.util.EnumMap<>(DesktopCompatibilityPolicy.Option.class);
    private Switch mOpenFilesWithSingleClick;
    private Switch mSystemDesktopMode;
    private TextView mSystemDesktopModeStatus;
    private View mResetCompatibilityDefaults;
    private Switch mMcpEnabled;
    private TextView mMcpStatus;
    private Switch mMcpNetworkEnabled;
    private TextView mMcpNetworkStatus;
    private boolean mRendering;
    private final java.util.EnumMap<IntegrationPackage, TextView> mIntegrationPackages =
            new java.util.EnumMap<>(IntegrationPackage.class);
    private View mTermuxX11Action;
    private TextView mConsoleFontSize;
    private final java.util.Map<Integer, View> mSections = new java.util.LinkedHashMap<>();
    private ScrollView mScroll;

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
        page.addView(centered(createHeader()), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        addSection(content, R.string.settings_section_desktop);
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

        addSection(content, R.string.settings_section_console);
        mConsoleFontSize = new TextView(mActivity);
        mConsoleFontSize.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mConsoleFontSize.setTextSize(12);
        addAction(content, android.R.drawable.ic_menu_zoom, R.string.settings_console_font_size,
                mActions::configureConsoleFontSize, mConsoleFontSize);

        addSection(content, R.string.settings_section_session);
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

        addSection(content, R.string.settings_section_compatibility);
        mResetCompatibilityDefaults = addAction(content, android.R.drawable.ic_menu_revert,
                R.string.settings_compat_reset, mActions::resetCompatibilityDefaults);
        mResetCompatibilityDefaults.setEnabled(false);
        for (final DesktopCompatibilityPolicy.Option option
                : DesktopCompatibilityPolicy.Option.values()) {
            final Switch control = addSwitch(content, compatibilityLabel(option));
            mCompatibility.put(option, control);
            control.setOnCheckedChangeListener((button, checked) -> {
                if (!mRendering) {
                    mActions.setCompatibilityOption(option, checked);
                }
            });
        }

        addSection(content, R.string.settings_section_android);
        mSystemDesktopMode = addSwitch(content, R.string.settings_system_desktop_mode);
        mSystemDesktopMode.setEnabled(false);
        mSystemDesktopMode.setOnCheckedChangeListener((button, checked) -> {
            if (!mRendering) {
                mActions.setSystemDesktopMode(checked);
            }
        });
        mSystemDesktopModeStatus = new TextView(mActivity);
        mSystemDesktopModeStatus.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mSystemDesktopModeStatus.setTextSize(12);
        mSystemDesktopModeStatus.setPadding(dp(8), dp(7), dp(8), dp(7));
        content.addView(mSystemDesktopModeStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addSection(content, R.string.settings_section_automation);
        mMcpEnabled = addSwitch(content, R.string.settings_mcp_enabled);
        mMcpEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (!mRendering) {
                mActions.setMcpEnabled(checked);
            }
        });
        addAction(content, android.R.drawable.ic_lock_lock,
                R.string.settings_mcp_local_access, () -> mActions.configureMcpAccess(false));
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

        mMcpNetworkEnabled = addSwitch(content, R.string.settings_mcp_network_enabled);
        mMcpNetworkEnabled.setOnCheckedChangeListener((button, checked) -> {
            if (!mRendering) mActions.setMcpNetworkEnabled(checked);
        });
        addAction(content, android.R.drawable.ic_lock_lock,
                R.string.settings_mcp_network_access, () -> mActions.configureMcpAccess(true));
        mMcpNetworkStatus = new TextView(mActivity);
        mMcpNetworkStatus.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mMcpNetworkStatus.setTextSize(12);
        mMcpNetworkStatus.setPadding(dp(8), dp(7), dp(8), dp(7));
        content.addView(mMcpNetworkStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        addAction(content, android.R.drawable.ic_menu_preferences,
                R.string.settings_mcp_network_configure, mActions::configureMcpNetwork);
        addAction(content, android.R.drawable.ic_menu_set_as,
                R.string.settings_mcp_network_copy, mActions::copyMcpNetworkConnection);
        addAction(content, android.R.drawable.ic_popup_sync,
                R.string.settings_mcp_network_token, mActions::regenerateMcpNetworkToken);

        addSection(content, R.string.settings_section_integrations);
        for (final IntegrationPackage integration : IntegrationPackage.values()) {
            final TextView value = new TextView(mActivity);
            value.setTextColor(DesktopUiFactory.COLOR_MUTED);
            value.setTextSize(12);
            value.setPadding(0, dp(4), 0, 0);
            addAction(content, android.R.drawable.ic_menu_edit, integrationLabel(integration),
                    () -> mActions.configureIntegrationPackage(integration), value);
            mIntegrationPackages.put(integration, value);
        }
        mTermuxX11Action = addAction(
                    content,
                    android.R.drawable.ic_menu_edit,
                    R.string.settings_termux_x11_command,
                    mActions::configureTermuxX11);

        addSection(content, R.string.settings_section_support);
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

        mScroll = new ScrollView(mActivity);
        mScroll.setFillViewport(true);
        mScroll.addView(centered(content), new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        page.addView(mScroll, scrollParams);
        return page;
    }

    private View centered(final View content) {
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
        return contentHost;
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
                || mMcpEnabled == null
                || mMcpStatus == null) {
            return;
        }
        mRendering = true;
        mConsoleFontSize.setText(mActivity.getString(R.string.console_font_size_value,
                ConsolePreferences.fontSizeSp(mActivity)));
        for (final IntegrationPackage integration : IntegrationPackage.values()) {
            final String saved = integration.configured(mActivity);
            mIntegrationPackages.get(integration).setText(saved.equals(integration.selected()) ? saved
                    : mActivity.getString(R.string.settings_integration_restart_pending, saved));
        }
        mTermuxX11Action.setVisibility(TermuxX11Integration.isAvailable(mActivity) ? View.VISIBLE : View.GONE);
        mTaskbarAutoHide.setChecked(settings.taskbarAutoHide);
        mOpenFilesWithSingleClick.setChecked(
                settings.openFilesWithSingleClick);
        mOpenTouchpadAutomatically.setChecked(
                settings.openTouchpadAutomatically);
        final DesktopCompatibilityPolicy compatibility = settings.compatibilityPolicy(
                PlatformDrivers.current().features());
        for (final DesktopCompatibilityPolicy.Option option : mCompatibility.keySet()) {
            mCompatibility.get(option).setChecked(compatibility.enabled(option));
        }
        mKeepDesktopAwake.setChecked(settings.keepDesktopAwake);
        mDisableAdaptiveBrightness.setChecked(
                settings.disableAdaptiveBrightnessOnExternalDesktop);
        mMcpEnabled.setChecked(mcp.enabled);
        mMcpNetworkEnabled.setChecked(mcp.enabled && mcp.networkEnabled);
        mMcpNetworkEnabled.setEnabled(mcp.enabled);
        mMcpNetworkStatus.setText(!mcp.networkEnabled
                ? mActivity.getString(R.string.settings_mcp_network_disabled)
                : runtime.networkRunning ? runtime.networkEndpoint
                : mActivity.getString(R.string.settings_mcp_network_unavailable,
                        runtime.networkError));
        final int status = runtime.running
                ? R.string.settings_mcp_status_running
                : mcp.enabled
                        ? R.string.settings_mcp_status_waiting
                        : R.string.settings_mcp_status_disabled;
        mMcpStatus.setText(mActivity.getString(status, mcp.endpoint()));
        mRendering = false;
    }

    void renderSystemDesktopMode(
            final Boolean enabled, final boolean canChange, final boolean busy,
            final int statusResId) {
        if (mSystemDesktopMode == null) {
            return;
        }
        mRendering = true;
        if (enabled != null) {
            mSystemDesktopMode.setChecked(enabled);
        }
        final boolean editable = enabled != null && canChange && !busy;
        mSystemDesktopMode.setEnabled(editable);
        mSystemDesktopMode.setAlpha(editable ? 1f : 0.5f);
        mResetCompatibilityDefaults.setEnabled(editable);
        mResetCompatibilityDefaults.setAlpha(editable ? 1f : 0.5f);
        for (final Switch control : mCompatibility.values()) {
            control.setEnabled(!busy);
        }
        mSystemDesktopModeStatus.setText(statusResId);
        mRendering = false;
    }

    private View createHeader() {
        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(46));

        final ImageView icon = new ImageView(mActivity);
        icon.setImageResource(R.drawable.ic_settings);
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
        final ImageButton sections = mUi.menuIconButton(R.drawable.ic_sections, R.string.settings_sections);
        sections.setOnClickListener(view -> {
            final PopupMenu menu = new PopupMenu(mActivity, sections);
            for (final int section : mSections.keySet()) {
                menu.getMenu().add(0, section, 0, section);
            }
            menu.setOnMenuItemClickListener(item -> {
                final View heading = mSections.get(item.getItemId());
                if (heading == null) { return false; }
                mScroll.smoothScrollTo(0, Math.max(0, heading.getTop() - dp(8)));
                return true;
            });
            menu.show();
        });
        header.addView(sections, new LinearLayout.LayoutParams(dp(48), dp(48)));
        return header;
    }

    private static int compatibilityLabel(final DesktopCompatibilityPolicy.Option option) {
        return switch (option) {
            case FOCUS_REPAIR -> R.string.settings_compat_focus_repair;
            case CAPTION_REFRESH -> R.string.settings_compat_caption_refresh;
            case ACTIVITY_HANDOFF_REPAIR -> R.string.settings_compat_activity_handoff;
            case PHONE_TASK_ISOLATION -> R.string.settings_compat_phone_isolation;
            case PHONE_TASK_RECOVERY -> R.string.settings_compat_phone_recovery;
            case STALE_RECENTS_CLEANUP -> R.string.settings_compat_stale_recents;
            case RECENTS_TO_HOME -> R.string.settings_compat_recents_home;
        };
    }

    private void addSection(
            final LinearLayout parent,
            final int titleResId) {
        final View divider = new View(mActivity);
        divider.setBackgroundColor(DesktopUiFactory.COLOR_MUTED);
        final LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        dividerParams.setMargins(0, dp(20), 0, dp(10));
        parent.addView(divider, dividerParams);
        final TextView title = mUi.sectionTitle(titleResId);
        title.setAccessibilityHeading(true);
        title.setTextSize(16);
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(8), 0, dp(8), dp(5));
        parent.addView(title, params);
        mSections.put(titleResId, title);
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

    static int integrationLabel(final IntegrationPackage integration) {
        return integration == IntegrationPackage.SHIZUKU
                ? R.string.settings_shizuku_package : R.string.settings_termux_package;
    }

    private View addAction(
            final LinearLayout parent,
            final int iconResId,
            final int labelResId,
            final Runnable action) {
        return addAction(parent, iconResId, labelResId, action, null);
    }

    private View addAction(
            final LinearLayout parent,
            final int iconResId,
            final int labelResId,
            final Runnable action,
            final TextView detail) {
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
        final LinearLayout text = new LinearLayout(mActivity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(0, dp(10), 0, dp(10));
        text.addView(label);
        if (detail != null) { text.addView(detail); }
        row.addView(text, labelParams);

        final ImageView arrow = new ImageView(mActivity);
        arrow.setImageResource(android.R.drawable.ic_media_next);
        arrow.setColorFilter(DesktopUiFactory.COLOR_MUTED);
        arrow.setContentDescription(null);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(18), dp(18)));

        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        addDivider(parent);
        return row;
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
