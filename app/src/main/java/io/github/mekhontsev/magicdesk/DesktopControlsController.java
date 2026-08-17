package io.github.mekhontsev.magicdesk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

final class DesktopControlsController {
    private static final int ACTION_BUTTON_HEIGHT_DP = 48;
    private static final int DPI_MIN = DisplayDensityPolicy.MIN_DPI;
    private static final int DPI_STEP = DisplayDensityPolicy.DPI_STEP;
    private static final int DPI_BUTTON_STEP = 8;
    private static final int DPI_BUTTON_SIZE_DP = 40;
    private static final String SELECTED_INPUT_METHOD_SUBTYPE =
            "selected_input_method_subtype";

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final DesktopAudioPanelController mAudio;
    private final PlatformSystemControls mPlatformControls;
    private final PointerSpeedPanelController mPointerSpeed;
    private final DisplayCapturePanelController mCapture;
    private Button mPhoneScreenAction;
    private Button mTouchpadAction;
    private SeekBar mDpiSlider;
    private TextView mDpiValue;
    private TextView mToolsStatus;
    private TextView mToolsActivityStatus;
    private ContentObserver mSettingsObserver;
    private ContentObserver mInputMethodSubtypeObserver;
    private BroadcastReceiver mBatteryReceiver;
    private String mLastStatusText;
    DesktopControlsController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mAudio = new DesktopAudioPanelController(activity, ui);
        mPlatformControls = PlatformDrivers.current()
                .createSystemControls(activity, ui);
        mPointerSpeed = new PointerSpeedPanelController(activity, ui);
        mCapture = new DisplayCapturePanelController(activity, ui);
    }

    void start() {
        registerBatteryReceiver();
        registerSettingsObserver();
        mAudio.start();
        mPlatformControls.start();
        mPointerSpeed.start();
        mCapture.start();
    }

    void stop() {
        if (mSettingsObserver != null) {
            mActivity.getContentResolver().unregisterContentObserver(
                    mSettingsObserver);
            mSettingsObserver = null;
        }
        if (mInputMethodSubtypeObserver != null) {
            mActivity.getContentResolver().unregisterContentObserver(
                    mInputMethodSubtypeObserver);
            mInputMethodSubtypeObserver = null;
        }
        if (mBatteryReceiver != null) {
            try {
                mActivity.unregisterReceiver(mBatteryReceiver);
            } catch (IllegalArgumentException ignored) {
                // The receiver may already be detached during teardown.
            }
            mBatteryReceiver = null;
        }
        mAudio.stop();
        mPlatformControls.stop();
        mPointerSpeed.stop();
        mCapture.stop();
    }

    void setActivityStatus(final String text) {
        mLastStatusText = text;
        if (mToolsActivityStatus != null) {
            mToolsActivityStatus.setText(text);
        }
    }

    void setHardwarePanelVisible(final boolean visible) {
        mPlatformControls.setPanelVisible(visible);
    }

    void populateTools(final LinearLayout parent, final int spacing) {
        mToolsStatus = new TextView(mActivity);
        mToolsStatus.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mToolsStatus.setTextSize(13);
        final LinearLayout.LayoutParams statusParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, spacing, 0, 0);
        parent.addView(mToolsStatus, statusParams);

        mToolsActivityStatus = new TextView(mActivity);
        mToolsActivityStatus.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mToolsActivityStatus.setTextSize(13);
        if (!TextUtils.isEmpty(mLastStatusText)) {
            mToolsActivityStatus.setText(mLastStatusText);
        }
        final LinearLayout.LayoutParams activityStatusParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        activityStatusParams.setMargins(0, spacing, 0, 0);
        parent.addView(mToolsActivityStatus, activityStatusParams);

        final GridLayout actionGrid = new GridLayout(mActivity);
        actionGrid.setColumnCount(2);

        final boolean externalDesktop =
                DesktopScreenPolicy.isExternalDesktop(
                        mActivity.getCurrentDisplayId());
        if (externalDesktop) {
            mPhoneScreenAction = mUi.actionButton(
                    R.string.action_phone_screen_off,
                    DesktopUiFactory.COLOR_CYAN);
            mPhoneScreenAction.setOnClickListener(view ->
                    togglePhoneScreen());
            addActionButton(actionGrid, mPhoneScreenAction);
        }

        final Button closeDesktop = mUi.actionButton(
                R.string.action_close_desktop,
                DesktopUiFactory.COLOR_CYAN);
        closeDesktop.setOnClickListener(view ->
                mActivity.closeDesktop());
        addActionButton(actionGrid, closeDesktop);

        if (externalDesktop) {
            mTouchpadAction = mUi.actionButton(
                    R.string.action_open_touchpad,
                    DesktopUiFactory.COLOR_CYAN);
            mTouchpadAction.setOnClickListener(view -> {
                mActivity.hideAllPanels();
                ConsoleModeSwitcher.openTouchpad();
            });
            addActionButton(actionGrid, mTouchpadAction);
        }

        final Button deviceSetup = mUi.actionButton(
                R.string.action_device_setup,
                DesktopUiFactory.COLOR_CYAN);
        deviceSetup.setOnClickListener(view ->
                mActivity.openDeviceSetup());
        addActionButton(actionGrid, deviceSetup);

        final Button controlPanel = mUi.actionButton(
                R.string.action_open_control_panel,
                DesktopUiFactory.COLOR_CYAN);
        controlPanel.setOnClickListener(view ->
                mActivity.openControlPanel());
        addActionButton(actionGrid, controlPanel);

        final Button diagnostics = mUi.actionButton(
                R.string.action_diagnostics,
                DesktopUiFactory.COLOR_CYAN);
        diagnostics.setOnClickListener(view ->
                mActivity.openDiagnostics());
        addActionButton(actionGrid, diagnostics);

        final Button console = mUi.actionButton(
                R.string.console_title,
                DesktopUiFactory.COLOR_CYAN);
        console.setOnClickListener(view ->
                mActivity.openConsole());
        addActionButton(actionGrid, console);

        final Button taskManager = mUi.actionButton(
                R.string.task_manager_title,
                DesktopUiFactory.COLOR_CYAN);
        taskManager.setOnClickListener(view ->
                mActivity.openTaskManager());
        addActionButton(actionGrid, taskManager);

        final Button settings = mUi.actionButton(
                R.string.action_settings,
                DesktopUiFactory.COLOR_CYAN);
        settings.setOnClickListener(view ->
                mActivity.openSettings());
        addActionButton(actionGrid, settings);

        final Button capture = mUi.actionButton(
                R.string.action_capture,
                DesktopUiFactory.COLOR_CYAN);
        capture.setOnClickListener(view ->
                mActivity.showCaptureControls());
        addActionButton(actionGrid, capture);

        final Button exit = mUi.actionButton(
                R.string.action_exit,
                DesktopUiFactory.COLOR_RED);
        exit.setOnClickListener(view -> mActivity.exitMagicDesk());
        addActionButton(actionGrid, exit);

        final LinearLayout.LayoutParams actionGridParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        actionGridParams.setMargins(0, spacing, 0, 0);
        parent.addView(actionGrid, actionGridParams);
        update();
    }

    void populateSystem(
            final LinearLayout parent,
            final int spacing) {
        final TextView displayTitle = mUi.sectionTitle(
                R.string.system_display_section);
        parent.addView(
                displayTitle,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        addDpiControls(parent);

        mPlatformControls.populate(parent, spacing);

        mPointerSpeed.populate(parent, spacing);
        mAudio.populate(parent, spacing);
    }

    void populateCapture(
            final LinearLayout parent,
            final int spacing) {
        mCapture.populate(parent, spacing);
    }

    void update() {
        mActivity.taskbar().updateKeyboardLayout();
        mPointerSpeed.refresh();

        final boolean consoleModeActive = isConsoleModeActive();
        final boolean externalDesktop =
                DesktopScreenPolicy.isExternalDesktop(
                        mActivity.getCurrentDisplayId());
        final int activeDesktopDisplayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final boolean externalDesktopSession =
                DesktopScreenPolicy.isExternalDesktopSession(
                        mActivity.getCurrentDisplayId(),
                        activeDesktopDisplayId,
                        consoleModeActive);
        final DesktopDisplayTarget target =
                DesktopRuntimeBridge.getDesktopTarget(
                        mActivity.getCurrentDisplayId());
        final boolean phoneScreenOff = isPhoneScreenOff();
        final boolean phoneScreenControl =
                DesktopScreenPolicy.canControlPhoneScreen(
                        externalDesktopSession,
                        target,
                        ShellAccess.isReady(),
                        PlatformDrivers.current().phoneUi().isAvailable());
        final int actionResId = phoneScreenOff
                ? R.string.action_phone_screen_on
                : R.string.action_phone_screen_off;
        mActivity.taskbar().updatePhoneScreen(
                phoneScreenOff, externalDesktop, phoneScreenControl);
        if (mPhoneScreenAction != null) {
            mPhoneScreenAction.setText(actionResId);
            mPhoneScreenAction.setEnabled(phoneScreenControl);
        }
        if (mToolsStatus != null) {
            final String shortcutsState = mActivity.getString(
                    KeyboardShortcutWatcher.isFullShortcutMode()
                            ? R.string.state_ready
                            : R.string.state_unavailable);
            mToolsStatus.setText(externalDesktopSession
                    ? mActivity.getString(
                            R.string.tools_status_full,
                            Integer.valueOf(mActivity.getCurrentDisplayId()),
                            mActivity.getString(phoneScreenOff
                                    ? R.string.state_off
                                    : R.string.state_on),
                            ShellAccess.statusLabel(),
                            shortcutsState,
                            mActivity.getDisplayProfileLabel())
                    : mActivity.getString(
                            R.string.tools_status_local,
                            Integer.valueOf(mActivity.getCurrentDisplayId()),
                            ShellAccess.statusLabel(),
                            shortcutsState));
        }
        if (mTouchpadAction != null) {
            final boolean touchpadSupported = target != null
                    && DesktopDisplayDrivers.forTarget(target)
                            .features().phoneTouchpad;
            mTouchpadAction.setEnabled(
                    externalDesktopSession
                            && touchpadSupported
                            && ShellAccess.isReady());
        }
        mActivity.taskbar().updateSystemStatus(
                KeyboardShortcutWatcher.isFullShortcutMode());
    }

    void togglePhoneScreen() {
        final int displayId = mActivity.getCurrentDisplayId();
        final boolean externalDesktopSession =
                DesktopScreenPolicy.isExternalDesktopSession(
                        displayId,
                        DesktopRuntimeBridge.getActiveDesktopDisplayId(),
                        isConsoleModeActive());
        if (!DesktopScreenPolicy.canControlPhoneScreen(
                externalDesktopSession,
                DesktopRuntimeBridge.getDesktopTarget(displayId),
                ShellAccess.isReady(),
                PlatformDrivers.current().phoneUi().isAvailable())) {
            return;
        }
        final boolean screenOff = !isPhoneScreenOff();
        mActivity.taskbar().setPhoneScreenActionEnabled(false);
        if (mPhoneScreenAction != null) {
            mPhoneScreenAction.setEnabled(false);
        }
        mActivity.setStatus(R.string.status_phone_screen_applying);
        ConsoleModeSwitcher.setPhoneScreenOff(
                screenOff,
                success -> mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    update();
                    final int resultResId;
                    if (!success) {
                        resultResId =
                                R.string.status_phone_screen_failed;
                    } else if (screenOff) {
                        resultResId =
                                R.string.status_phone_screen_off;
                    } else {
                        resultResId =
                                R.string.status_phone_screen_on;
                    }
                    if (success) {
                        mActivity.setStatus(resultResId);
                    } else {
                        mActivity.setErrorStatus(
                                "PHONE-SCREEN-001",
                                mActivity.getString(resultResId));
                    }
                }));
    }

    private boolean isPhoneScreenOff() {
        return PlatformDrivers.current().phoneUi()
                .isPhoneScreenOff(mActivity);
    }

    private boolean isConsoleModeActive() {
        return PlatformDrivers.current().projection()
                .activeDesktopDisplayId(mActivity) > 0;
    }

    private void registerBatteryReceiver() {
        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(
                    final Context context,
                    final Intent intent) {
                mActivity.taskbar().updateBattery(intent);
                mPlatformControls.onBatteryChanged(intent);
            }
        };
        final Intent battery = mActivity.registerReceiver(
                mBatteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            mActivity.taskbar().updateBattery(battery);
            mPlatformControls.onBatteryChanged(battery);
        }
    }

    private void registerSettingsObserver() {
        mSettingsObserver = new ContentObserver(
                new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(final boolean selfChange) {
                update();
                mActivity.scheduleDisplayProfileRefresh();
            }
        };
        registerSetting(DesktopShellActivity.HARDWARE_LAYOUT_STATE);
        registerSetting(DesktopShellActivity.HARDWARE_LAYOUT_LABEL_STATE);
        registerSetting(DesktopShellActivity.HARDWARE_LAYOUT_NAME_STATE);
        for (final String setting : PlatformDrivers.current().phoneUi()
                .observedSettingKeys()) {
            registerSetting(setting);
        }
        for (final String setting : PlatformDrivers.current().projection()
                .observedSettingKeys()) {
            registerSetting(setting);
        }

        mInputMethodSubtypeObserver = new ContentObserver(
                new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(final boolean selfChange) {
                HardwareKeyboardLayoutController.syncWithInputMethod();
            }
        };
        mActivity.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(
                        SELECTED_INPUT_METHOD_SUBTYPE),
                false,
                mInputMethodSubtypeObserver);
    }

    private void registerSetting(final String key) {
        mActivity.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(key),
                false,
                mSettingsObserver);
    }

    private void addDpiControls(final LinearLayout parent) {
        final int maximum = Math.max(
                DPI_MIN, DisplayMetrics.DENSITY_DEVICE_STABLE);
        final int current = clampDpi(mActivity.getResources()
                .getDisplayMetrics().densityDpi, maximum);
        final boolean enabled = ShellAccess.isReady();

        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView label = new TextView(mActivity);
        label.setText(R.string.dpi_label);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(14);
        header.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        mDpiValue = new TextView(mActivity);
        mDpiValue.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mDpiValue.setTextSize(14);
        mDpiValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        updateDpiValue(current);
        header.addView(mDpiValue, new LinearLayout.LayoutParams(
                dp(72), LinearLayout.LayoutParams.WRAP_CONTENT));
        parent.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final LinearLayout adjustment = new LinearLayout(mActivity);
        adjustment.setOrientation(LinearLayout.HORIZONTAL);
        adjustment.setGravity(Gravity.CENTER_VERTICAL);

        final Button decrease = dpiStepButton(
                "-", R.string.action_dpi_decrease, enabled);
        decrease.setOnClickListener(view -> adjustDpi(-DPI_BUTTON_STEP));
        adjustment.addView(decrease, dpiStepButtonParams());

        mDpiSlider = new SeekBar(mActivity);
        mDpiSlider.setMin(DPI_MIN);
        mDpiSlider.setMax(maximum);
        mDpiSlider.setKeyProgressIncrement(DPI_STEP);
        mDpiSlider.setSplitTrack(false);
        mDpiSlider.setProgress(snapDpi(current, maximum));
        mDpiSlider.setEnabled(enabled);
        mDpiSlider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            final SeekBar seekBar,
                            final int progress,
                            final boolean fromUser) {
                        final int snapped = snapDpi(progress, maximum);
                        if (fromUser && progress != snapped) {
                            seekBar.setProgress(snapped);
                            return;
                        }
                        updateDpiValue(snapped);
                    }

                    @Override
                    public void onStartTrackingTouch(final SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(final SeekBar seekBar) {
                        final int dpi = snapDpi(
                                seekBar.getProgress(), maximum);
                        seekBar.setProgress(dpi);
                        applyDpiIfChanged(dpi);
                    }
                });
        adjustment.addView(mDpiSlider, new LinearLayout.LayoutParams(
                0, dp(DPI_BUTTON_SIZE_DP), 1));

        final Button increase = dpiStepButton(
                "+", R.string.action_dpi_increase, enabled);
        increase.setOnClickListener(view -> adjustDpi(DPI_BUTTON_STEP));
        adjustment.addView(increase, dpiStepButtonParams());
        parent.addView(adjustment, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final LinearLayout footer = new LinearLayout(mActivity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setGravity(Gravity.CENTER_VERTICAL);

        final TextView range = new TextView(mActivity);
        range.setText(mActivity.getString(
                R.string.dpi_range,
                Integer.valueOf(DPI_MIN),
                Integer.valueOf(maximum)));
        range.setTextColor(DesktopUiFactory.COLOR_MUTED);
        range.setTextSize(11);
        footer.addView(range, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final int recommendedDpi = mActivity.getRecommendedDesktopDpi();
        final int recommendedLabel = recommendedDpi
                == DesktopPreferences.SYSTEM_DESKTOP_DPI
                ? DisplayMetrics.DENSITY_DEVICE_STABLE : recommendedDpi;
        final Button defaultDpi = mUi.smallButton(
                Integer.toString(recommendedLabel),
                DesktopUiFactory.COLOR_CYAN);
        defaultDpi.setContentDescription(
                mActivity.getString(R.string.action_dpi_default));
        defaultDpi.setTooltipText(
                mActivity.getString(R.string.action_dpi_default));
        defaultDpi.setEnabled(enabled);
        defaultDpi.setOnClickListener(view ->
                mActivity.applyRecommendedDensity());
        footer.addView(defaultDpi, dpiFooterButtonParams(dp(112)));

        final Button systemDpi = mUi.smallButton(
                R.string.action_dpi_system,
                DesktopUiFactory.COLOR_PANEL_ALT);
        systemDpi.setEnabled(enabled);
        systemDpi.setOnClickListener(view -> mActivity.resetDensity());
        footer.addView(systemDpi, dpiFooterButtonParams(dp(82)));
        parent.addView(footer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private Button dpiStepButton(
            final String text,
            final int descriptionResId,
            final boolean enabled) {
        final Button button = mUi.smallButton(
                text, DesktopUiFactory.COLOR_PANEL_ALT);
        button.setTextSize(16);
        button.setContentDescription(
                mActivity.getString(descriptionResId));
        button.setTooltipText(mActivity.getString(descriptionResId));
        button.setEnabled(enabled);
        return button;
    }

    private LinearLayout.LayoutParams dpiStepButtonParams() {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        dp(DPI_BUTTON_SIZE_DP),
                        dp(DPI_BUTTON_SIZE_DP));
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private LinearLayout.LayoutParams dpiFooterButtonParams(
            final int width) {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(width, dp(DPI_BUTTON_SIZE_DP));
        params.setMargins(dp(4), dp(2), 0, dp(2));
        return params;
    }

    private void adjustDpi(final int delta) {
        final int maximum = Math.max(
                DPI_MIN, DisplayMetrics.DENSITY_DEVICE_STABLE);
        final int current = mActivity.getResources()
                .getDisplayMetrics().densityDpi;
        final int target = snapDpi(current + delta, maximum);
        if (mDpiSlider != null) {
            mDpiSlider.setProgress(target);
        }
        applyDpiIfChanged(target);
    }

    private void applyDpiIfChanged(final int dpi) {
        if (dpi != mActivity.getResources()
                .getDisplayMetrics().densityDpi) {
            mActivity.applyDensity(dpi);
        }
    }

    private void updateDpiValue(final int dpi) {
        if (mDpiValue != null) {
            mDpiValue.setText(mActivity.getString(
                    R.string.dpi_value, Integer.valueOf(dpi)));
        }
    }

    static int snapDpi(final int dpi, final int maximum) {
        return DisplayDensityPolicy.snapDpi(dpi, maximum);
    }

    private static int clampDpi(final int dpi, final int maximum) {
        return Math.max(DPI_MIN, Math.min(maximum, dpi));
    }

    private void addActionButton(
            final GridLayout grid,
            final Button button) {
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), dp(3), dp(8), dp(3));
        button.setGravity(Gravity.CENTER);
        final GridLayout.LayoutParams params =
                new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(ACTION_BUTTON_HEIGHT_DP);
        params.rowSpec =
                GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL);
        params.columnSpec =
                GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(button, params);
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
