package io.github.mekhontsev.magicdesk;

import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

final class RedmagicHardwarePanelController
        implements RedmagicHardwareController.Listener {
    private static final int MODE_BUTTON_HEIGHT_DP = 40;
    private static final int LEVEL_ROW_HEIGHT_DP = 40;
    private static final int PUMP_SPEED_MIN = 1;
    private static final int PUMP_SPEED_MAX = 3;

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final Map<RedmagicHardwareController.FanMode, Button> mFanButtons =
            new EnumMap<>(RedmagicHardwareController.FanMode.class);
    private final Map<RedmagicHardwareController.PumpMode, Button> mPumpButtons =
            new EnumMap<>(RedmagicHardwareController.PumpMode.class);

    private TextView mStatus;
    private TextView mFanStatus;
    private TextView mPumpStatus;
    private TextView mPumpSpeedStatus;
    private LinearLayout mFanSection;
    private LinearLayout mPumpSection;
    private Button mPumpManual;
    private SeekBar mPumpSpeed;
    private boolean mUpdatingControls;
    private boolean mMonitoringActive;

    RedmagicHardwarePanelController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    void start() {
        RedmagicHardwareController.addListener(this);
    }

    void stop() {
        setMonitoringActive(false);
        RedmagicHardwareController.removeListener(this);
    }

    void setMonitoringActive(final boolean active) {
        if (mMonitoringActive == active) {
            return;
        }
        mMonitoringActive = active;
        RedmagicHardwareController.setMonitoringEnabled(this, active);
    }

    void populate(final LinearLayout parent, final int spacing) {
        mFanButtons.clear();
        mPumpButtons.clear();

        addHeading(parent, R.string.hardware_section_title, spacing);
        mStatus = statusText();
        parent.addView(mStatus, matchWidth());

        mFanSection = new LinearLayout(mActivity);
        mFanSection.setOrientation(LinearLayout.VERTICAL);
        mFanStatus = addLabel(mFanSection, R.string.hardware_fan);
        final GridLayout fanModes = modeGrid(4);
        addFanButton(fanModes, R.string.hardware_system,
                RedmagicHardwareController.FanMode.SYSTEM);
        addFanButton(fanModes, R.string.hardware_auto,
                RedmagicHardwareController.FanMode.AUTO);
        addFanButton(fanModes, R.string.hardware_off,
                RedmagicHardwareController.FanMode.OFF);
        addFanButton(fanModes, R.string.hardware_extreme,
                RedmagicHardwareController.FanMode.EXTREME);
        mFanSection.addView(fanModes, matchWidth());
        parent.addView(mFanSection, matchWidth());

        mPumpSection = new LinearLayout(mActivity);
        mPumpSection.setOrientation(LinearLayout.VERTICAL);
        mPumpStatus = addLabel(mPumpSection, R.string.hardware_pump);
        final GridLayout pumpModes = modeGrid(3);
        addPumpButton(pumpModes, R.string.hardware_system,
                RedmagicHardwareController.PumpMode.SYSTEM);
        addPumpButton(pumpModes, R.string.hardware_off,
                RedmagicHardwareController.PumpMode.OFF);
        mPumpManual = createModeButton(R.string.hardware_manual);
        mPumpManual.setOnClickListener(view ->
                applyPumpMode(pumpModeForSpeed(mPumpSpeed.getProgress())));
        pumpModes.addView(mPumpManual, modeGridParams());
        mPumpSection.addView(pumpModes, matchWidth());

        mPumpSpeed = levelSlider(PUMP_SPEED_MIN, PUMP_SPEED_MAX);
        mPumpSpeedStatus = levelStatus();
        mPumpSpeed.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            final SeekBar seekBar,
                            final int progress,
                            final boolean fromUser) {
                        updatePumpSpeedStatus(progress);
                    }

                    @Override
                    public void onStartTrackingTouch(final SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(final SeekBar seekBar) {
                        if (!mUpdatingControls) {
                            applyPumpMode(pumpModeForSpeed(
                                    seekBar.getProgress()));
                        }
                    }
                });
        mPumpSection.addView(
                levelRow(mPumpSpeedStatus, mPumpSpeed), matchWidth());
        parent.addView(mPumpSection, matchWidth());

        update(RedmagicHardwareController.snapshot());
    }

    @Override
    public void onHardwareStateChanged(
            final RedmagicHardwareSnapshot snapshot) {
        update(snapshot);
    }

    private void update(final RedmagicHardwareSnapshot snapshot) {
        if (mStatus == null) {
            return;
        }
        final boolean monitoringAllowed = ShellAccess.isReady();
        final boolean controlsAllowed = ShellAccess.isReady();
        final boolean available = monitoringAllowed && snapshot.isAvailable();
        if (!available) {
            mStatus.setText(monitoringAllowed
                    ? R.string.hardware_unavailable
                    : R.string.hardware_privileged_required);
        } else {
            final String status = mActivity.getString(
                    R.string.hardware_status,
                    temperature(snapshot.cpuMilliCelsius),
                    temperature(snapshot.gpuMilliCelsius),
                    temperature(snapshot.skinMilliCelsius),
                    temperature(snapshot.batteryMilliCelsius));
            mStatus.setText(controlsAllowed
                    ? status
                    : mActivity.getString(
                            R.string.hardware_monitoring_only, status));
        }

        if (mFanStatus != null) {
            mFanStatus.setText(fanStatus(snapshot));
        }
        if (mPumpStatus != null) {
            mPumpStatus.setText(mActivity.getString(
                    R.string.hardware_pump_status,
                    pumpStatus(snapshot)));
        }
        if (mFanSection != null) {
            mFanSection.setVisibility(
                    snapshot.fanAvailable ? View.VISIBLE : View.GONE);
        }
        if (mPumpSection != null) {
            mPumpSection.setVisibility(
                    snapshot.pumpAvailable ? View.VISIBLE : View.GONE);
        }

        final RedmagicHardwareController.FanMode fanMode =
                RedmagicHardwareController.fanMode();
        final RedmagicHardwareController.PumpMode pumpMode =
                RedmagicHardwareController.pumpMode();
        mUpdatingControls = true;
        if (mPumpSpeed != null) {
            mPumpSpeed.setProgress(resolvePumpSpeed(snapshot, pumpMode));
            updatePumpSpeedStatus(mPumpSpeed.getProgress());
        }
        mUpdatingControls = false;

        updateModeButtons(mFanButtons, fanMode,
                controlsAllowed && snapshot.fanAvailable);
        updateModeButtons(mPumpButtons, pumpMode,
                controlsAllowed && snapshot.pumpAvailable);
        updateModeButton(mPumpManual, isManual(pumpMode),
                controlsAllowed && snapshot.pumpAvailable);
        if (mPumpSpeed != null) {
            mPumpSpeed.setEnabled(controlsAllowed && snapshot.pumpAvailable);
            mPumpSpeed.setAlpha(isManual(pumpMode) ? 1f : 0.72f);
        }
    }

    private void addFanButton(
            final GridLayout grid,
            final int textResId,
            final RedmagicHardwareController.FanMode mode) {
        final Button button = createModeButton(textResId);
        button.setOnClickListener(view -> applyFanMode(mode));
        mFanButtons.put(mode, button);
        grid.addView(button, modeGridParams());
    }

    private void addPumpButton(
            final GridLayout grid,
            final int textResId,
            final RedmagicHardwareController.PumpMode mode) {
        final Button button = createModeButton(textResId);
        button.setOnClickListener(view -> applyPumpMode(mode));
        mPumpButtons.put(mode, button);
        grid.addView(button, modeGridParams());
    }

    private void applyFanMode(
            final RedmagicHardwareController.FanMode mode) {
        setFanControlsEnabled(false);
        RedmagicHardwareController.setFanMode(
                mode,
                success -> {
                    if (!success) {
                        mActivity.setErrorStatus(
                                "REDMAGIC-HW-FAN-001",
                                mActivity.getString(
                                        R.string.hardware_write_failed));
                    }
                    update(RedmagicHardwareController.snapshot());
                });
    }

    private void applyPumpMode(
            final RedmagicHardwareController.PumpMode mode) {
        setPumpControlsEnabled(false);
        RedmagicHardwareController.setPumpMode(
                mode,
                success -> {
                    if (!success) {
                        mActivity.setErrorStatus(
                                "REDMAGIC-HW-PUMP-001",
                                mActivity.getString(
                                        R.string.hardware_write_failed));
                    }
                    update(RedmagicHardwareController.snapshot());
                });
    }

    private Button createModeButton(final int textResId) {
        final Button button = mUi.actionButton(
                textResId, DesktopUiFactory.COLOR_PANEL_ALT);
        button.setTextSize(12);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(5), dp(2), dp(5), dp(2));
        button.setGravity(Gravity.CENTER);
        return button;
    }

    private GridLayout modeGrid(final int columns) {
        final GridLayout grid = new GridLayout(mActivity);
        grid.setColumnCount(columns);
        return grid;
    }

    private GridLayout.LayoutParams modeGridParams() {
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(MODE_BUTTON_HEIGHT_DP);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(2), dp(2), dp(2), dp(2));
        return params;
    }

    private SeekBar levelSlider(final int minimum, final int maximum) {
        final SeekBar slider = new SeekBar(mActivity);
        slider.setMin(minimum);
        slider.setMax(maximum);
        slider.setKeyProgressIncrement(1);
        slider.setSplitTrack(false);
        return slider;
    }

    private LinearLayout levelRow(
            final TextView status, final SeekBar slider) {
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(status, new LinearLayout.LayoutParams(
                dp(118), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(slider, new LinearLayout.LayoutParams(
                0, dp(LEVEL_ROW_HEIGHT_DP), 1));
        return row;
    }

    private TextView levelStatus() {
        final TextView status = new TextView(mActivity);
        status.setTextColor(DesktopUiFactory.COLOR_MUTED);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER_VERTICAL);
        return status;
    }

    private TextView statusText() {
        final TextView status = new TextView(mActivity);
        status.setTextColor(DesktopUiFactory.COLOR_MUTED);
        status.setTextSize(13);
        return status;
    }

    private void addHeading(
            final LinearLayout parent,
            final int textResId,
            final int spacing) {
        final TextView heading = new TextView(mActivity);
        heading.setText(textResId);
        heading.setTextColor(DesktopUiFactory.COLOR_TEXT);
        heading.setTextSize(14);
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, spacing, 0, dp(4));
        parent.addView(heading, params);
    }

    private TextView addLabel(
            final LinearLayout parent, final int textResId) {
        final TextView label = new TextView(mActivity);
        label.setText(textResId);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(13);
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(7), 0, 0);
        parent.addView(label, params);
        return label;
    }

    private void updatePumpSpeedStatus(final int speed) {
        if (mPumpSpeedStatus != null) {
            mPumpSpeedStatus.setText(mActivity.getString(
                    R.string.hardware_pump_speed,
                    mActivity.getString(pumpSpeedLabel(speed))));
        }
    }

    private int resolvePumpSpeed(
            final RedmagicHardwareSnapshot snapshot,
            final RedmagicHardwareController.PumpMode mode) {
        if (isManual(mode)) {
            return mode == RedmagicHardwareController.PumpMode.SLOW
                    ? 1 : (mode == RedmagicHardwareController.PumpMode.MEDIUM
                            ? 2 : 3);
        }
        final int speed = snapshot.pumpSpeed;
        if (speed == RedmagicHardwareSnapshot.UNKNOWN) {
            return 2;
        }
        return speed < 50 ? 1 : (speed < 70 ? 2 : 3);
    }

    private static RedmagicHardwareController.PumpMode pumpModeForSpeed(
            final int speed) {
        if (speed <= 1) {
            return RedmagicHardwareController.PumpMode.SLOW;
        }
        return speed == 2
                ? RedmagicHardwareController.PumpMode.MEDIUM
                : RedmagicHardwareController.PumpMode.FAST;
    }

    private static int pumpSpeedLabel(final int speed) {
        if (speed <= 1) {
            return R.string.hardware_speed_slow;
        }
        return speed == 2
                ? R.string.hardware_speed_medium
                : R.string.hardware_speed_fast;
    }

    private static boolean isManual(
            final RedmagicHardwareController.PumpMode mode) {
        return mode == RedmagicHardwareController.PumpMode.SLOW
                || mode == RedmagicHardwareController.PumpMode.MEDIUM
                || mode == RedmagicHardwareController.PumpMode.FAST;
    }

    private <T extends Enum<T>> void updateModeButtons(
            final Map<T, Button> buttons,
            final T selected,
            final boolean enabled) {
        for (final Map.Entry<T, Button> entry : buttons.entrySet()) {
            updateModeButton(
                    entry.getValue(), entry.getKey() == selected, enabled);
        }
    }

    private void updateModeButton(
            final Button button,
            final boolean selected,
            final boolean enabled) {
        if (button == null) {
            return;
        }
        button.setEnabled(enabled);
        button.setAlpha(selected ? 1f : 0.72f);
        button.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                dp(8),
                selected
                        ? DesktopUiFactory.COLOR_CYAN
                        : DesktopUiFactory.COLOR_PANEL_ALT));
    }

    private void setFanControlsEnabled(final boolean enabled) {
        setEnabled(mFanButtons, enabled);
    }

    private void setPumpControlsEnabled(final boolean enabled) {
        setEnabled(mPumpButtons, enabled);
        if (mPumpManual != null) {
            mPumpManual.setEnabled(enabled);
        }
        if (mPumpSpeed != null) {
            mPumpSpeed.setEnabled(enabled);
        }
    }

    private static <T extends Enum<T>> void setEnabled(
            final Map<T, Button> buttons, final boolean enabled) {
        for (final Button button : buttons.values()) {
            button.setEnabled(enabled);
        }
    }

    private String temperature(final int milliCelsius) {
        if (milliCelsius == RedmagicHardwareSnapshot.UNKNOWN) {
            return "--";
        }
        return String.format(
                Locale.ROOT, "%.1f", milliCelsius / 1000f);
    }

    private String fanStatus(final RedmagicHardwareSnapshot snapshot) {
        if (snapshot.fanAvailable) {
            return mActivity.getString(
                    R.string.hardware_fan_policy_status,
                    mActivity.getString(snapshot.fanEnabled == 1
                            ? R.string.state_on : R.string.state_off));
        }
        return mActivity.getString(
                R.string.hardware_fan_policy_status, "--");
    }

    private String pumpStatus(final RedmagicHardwareSnapshot snapshot) {
        if (!snapshot.pumpAvailable) {
            return mActivity.getString(R.string.state_unavailable);
        }
        return snapshot.pumpEnabled == 1
                ? mActivity.getString(
                        R.string.hardware_pump_running,
                        Integer.valueOf(snapshot.pumpSpeed))
                : mActivity.getString(R.string.state_off);
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
