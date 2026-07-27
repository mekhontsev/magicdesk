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
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

final class ConsoleControlsController {
    private static final String TAG = "MagicDesk";
    private static final String PHONE_SCREEN_OFF_STATE =
            "nubia_screen_off_tp";

    private final MainActivity mActivity;
    private final DesktopUiFactory mUi;
    private final Set<Button> mConsoleModeActions =
            Collections.newSetFromMap(
                    new WeakHashMap<Button, Boolean>());

    private Button mPhoneScreenAction;
    private TextView mToolsStatus;
    private TextView mToolsActivityStatus;
    private ContentObserver mSettingsObserver;
    private BroadcastReceiver mBatteryReceiver;
    private String mLastStatusText;

    ConsoleControlsController(
            final MainActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    void start() {
        registerBatteryReceiver();
        registerSettingsObserver();
    }

    void stop() {
        if (mSettingsObserver != null) {
            mActivity.getContentResolver().unregisterContentObserver(
                    mSettingsObserver);
            mSettingsObserver = null;
        }
        if (mBatteryReceiver != null) {
            try {
                mActivity.unregisterReceiver(mBatteryReceiver);
            } catch (IllegalArgumentException ignored) {
                // The receiver may already be detached during teardown.
            }
            mBatteryReceiver = null;
        }
    }

    void setActivityStatus(final String text) {
        mLastStatusText = text;
        if (mToolsActivityStatus != null) {
            mToolsActivityStatus.setText(text);
        }
    }

    void populate(final LinearLayout parent, final int spacing) {
        final TextView dpiLabel = new TextView(mActivity);
        dpiLabel.setText(R.string.dpi_label);
        dpiLabel.setTextColor(DesktopUiFactory.COLOR_TEXT);
        dpiLabel.setTextSize(14);
        final LinearLayout.LayoutParams dpiLabelParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        dpiLabelParams.setMargins(0, 0, 0, dp(6));
        parent.addView(dpiLabel, dpiLabelParams);

        final GridLayout dpiGrid = new GridLayout(mActivity);
        dpiGrid.setColumnCount(5);
        addDpiButton(dpiGrid, 160);
        addDpiButton(dpiGrid, 192);
        addDpiButton(dpiGrid, 240);
        addDpiButton(dpiGrid, 320);
        addDpiResetButton(dpiGrid);
        parent.addView(dpiGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

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

        mPhoneScreenAction = mUi.actionButton(
                R.string.action_phone_screen_off,
                DesktopUiFactory.COLOR_CYAN);
        mPhoneScreenAction.setOnClickListener(view ->
                togglePhoneScreen());
        addActionButton(actionGrid, mPhoneScreenAction);

        final Button consoleMode = mUi.actionButton(
                R.string.action_switch_to_mirror,
                DesktopUiFactory.COLOR_CYAN);
        mConsoleModeActions.add(consoleMode);
        consoleMode.setOnClickListener(view -> toggleConsoleMode());
        addActionButton(actionGrid, consoleMode);

        final Button restartShortcuts = mUi.actionButton(
                R.string.action_restart_shortcuts,
                DesktopUiFactory.COLOR_AMBER);
        restartShortcuts.setOnClickListener(view ->
                mActivity.restartConsoleShortcuts());
        restartShortcuts.setEnabled(RuntimeAccess.has(
                RuntimeAccess.Capability.GLOBAL_INPUT));
        addActionButton(actionGrid, restartShortcuts);

        final Button deviceSetup = mUi.actionButton(
                R.string.action_device_setup,
                DesktopUiFactory.COLOR_CYAN);
        deviceSetup.setOnClickListener(view -> {
            mActivity.hideAllPanels();
            mActivity.startActivity(
                    DeviceSetupActivity.createManualIntent(mActivity));
        });
        addActionButton(actionGrid, deviceSetup);

        final Button diagnostics = mUi.actionButton(
                R.string.action_diagnostics,
                DesktopUiFactory.COLOR_CYAN);
        diagnostics.setOnClickListener(view ->
                mActivity.openDiagnostics());
        addActionButton(actionGrid, diagnostics);

        if (RuntimeAccess.has(RuntimeAccess.Capability.KERNEL_FIXES)
                && KernelFixesIntegration.isAvailable(mActivity)) {
            addActionButton(
                    actionGrid,
                    mActivity.createKernelFixesAction());
        }

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

    void update() {
        mActivity.taskbar().updateKeyboardLayout();

        final boolean phoneScreenOff = isPhoneScreenOff();
        final boolean phoneScreenControl = RuntimeAccess.has(
                RuntimeAccess.Capability.PHONE_SCREEN_CONTROL);
        final int actionResId = phoneScreenOff
                ? R.string.action_phone_screen_on
                : R.string.action_phone_screen_off;
        mActivity.taskbar().updatePhoneScreen(
                phoneScreenOff, phoneScreenControl);
        if (mPhoneScreenAction != null) {
            mPhoneScreenAction.setText(actionResId);
            mPhoneScreenAction.setEnabled(phoneScreenControl);
        }
        if (mToolsStatus != null) {
            mToolsStatus.setText(mActivity.getString(
                    R.string.tools_status_full,
                    Integer.valueOf(mActivity.getCurrentDisplayId()),
                    Integer.valueOf(mActivity.getResources()
                            .getDisplayMetrics().densityDpi),
                    mActivity.getString(phoneScreenOff
                            ? R.string.state_off
                            : R.string.state_on),
                    RuntimeAccess.backendName(),
                    mActivity.getString(
                            RootKeyboardShortcutWatcher.isRunning()
                                    ? R.string.state_ready
                                    : R.string.state_unavailable),
                    mActivity.getMonitorProfileLabel()));
        }
        final boolean consoleModeActive = isConsoleModeActive();
        final boolean consoleControl = RuntimeAccess.has(
                RuntimeAccess.Capability.CONSOLE_CONTROL);
        for (final Button action : mConsoleModeActions) {
            action.setText(consoleModeActive
                    ? R.string.action_switch_to_mirror
                    : R.string.action_start_console_mode);
            action.setEnabled(consoleControl);
        }
        mActivity.taskbar().updateSystemStatus(
                consoleModeActive,
                RootKeyboardShortcutWatcher.isRunning());
    }

    void togglePhoneScreen() {
        if (!RuntimeAccess.has(
                RuntimeAccess.Capability.PHONE_SCREEN_CONTROL)) {
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
                                "NUBIA-SCREEN-001",
                                mActivity.getString(resultResId));
                    }
                }));
    }

    private void toggleConsoleMode() {
        if (!RuntimeAccess.has(
                RuntimeAccess.Capability.CONSOLE_CONTROL)) {
            return;
        }
        if (!isConsoleModeActive()) {
            mActivity.setStatus(R.string.status_console_starting);
            ConsoleModeSwitcher.showMagicDesk();
            return;
        }

        for (final Button action : mConsoleModeActions) {
            action.setEnabled(false);
        }
        mActivity.setStatus(R.string.status_mirror_switching);
        ConsoleModeSwitcher.switchToMirror(
                success -> mActivity.runOnUiThread(() -> {
                    update();
                    final int result = success
                            ? R.string.status_mirror_active
                            : R.string.status_mirror_failed;
                    if (success) {
                        mActivity.setStatus(result);
                    } else {
                        mActivity.setErrorStatus(
                                "NUBIA-CONSOLE-001",
                                mActivity.getString(result));
                    }
                }));
    }

    private boolean isPhoneScreenOff() {
        try {
            return Settings.Global.getInt(
                    mActivity.getContentResolver(),
                    PHONE_SCREEN_OFF_STATE,
                    0) == 1;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read phone screen state", e);
            return false;
        }
    }

    private boolean isConsoleModeActive() {
        try {
            return Settings.Global.getInt(
                    mActivity.getContentResolver(),
                    "app_mirror_displayid",
                    -1) > 0;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot read Console Mode state", e);
            return false;
        }
    }

    private void registerBatteryReceiver() {
        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(
                    final Context context,
                    final Intent intent) {
                mActivity.taskbar().updateBattery(intent);
            }
        };
        final Intent battery = mActivity.registerReceiver(
                mBatteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            mActivity.taskbar().updateBattery(battery);
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
        registerSetting(MainActivity.HARDWARE_LAYOUT_STATE);
        registerSetting(MainActivity.HARDWARE_LAYOUT_LABEL_STATE);
        registerSetting(MainActivity.HARDWARE_LAYOUT_NAME_STATE);
        registerSetting(PHONE_SCREEN_OFF_STATE);
        registerSetting("app_mirror_displayid");
    }

    private void registerSetting(final String key) {
        mActivity.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(key),
                false,
                mSettingsObserver);
    }

    private void addDpiButton(
            final GridLayout grid,
            final int dpi) {
        final Button button = mUi.actionButton(
                mActivity.getString(
                        R.string.dpi_value, Integer.valueOf(dpi)),
                DesktopUiFactory.COLOR_CYAN);
        button.setOnClickListener(view -> mActivity.applyDensity(dpi));
        button.setEnabled(RuntimeAccess.has(
                RuntimeAccess.Capability.DISPLAY_OVERRIDES));
        grid.addView(button, createDpiButtonParams());
    }

    private void addDpiResetButton(final GridLayout grid) {
        final Button button = mUi.actionButton(
                R.string.action_dpi_reset,
                DesktopUiFactory.COLOR_RED);
        button.setOnClickListener(view -> mActivity.resetDensity());
        button.setEnabled(RuntimeAccess.has(
                RuntimeAccess.Capability.DISPLAY_OVERRIDES));
        grid.addView(button, createDpiButtonParams());
    }

    private GridLayout.LayoutParams createDpiButtonParams() {
        final GridLayout.LayoutParams params =
                new GridLayout.LayoutParams();
        params.width = 0;
        params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        params.columnSpec =
                GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private void addActionButton(
            final GridLayout grid,
            final Button button) {
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setGravity(Gravity.CENTER);
        final GridLayout.LayoutParams params =
                new GridLayout.LayoutParams();
        params.width = 0;
        params.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        params.columnSpec =
                GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(button, params);
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
