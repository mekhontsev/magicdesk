package io.github.mekhontsev.magicdesk;

import android.view.Gravity;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

final class RedmagicHardwarePanelController
        implements RedmagicHardwareController.Listener {
    private static final int BUTTON_HEIGHT_DP = 44;

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final Map<RedmagicHardwareController.FanMode, Button> mFanButtons =
            new EnumMap<>(RedmagicHardwareController.FanMode.class);
    private final Map<RedmagicHardwareController.PumpMode, Button> mPumpButtons =
            new EnumMap<>(RedmagicHardwareController.PumpMode.class);

    private TextView mStatus;

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
        RedmagicHardwareController.removeListener(this);
    }

    void populate(final LinearLayout parent, final int spacing) {
        addHeading(parent, R.string.hardware_section_title, spacing);
        mStatus = new TextView(mActivity);
        mStatus.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mStatus.setTextSize(13);
        parent.addView(mStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        addLabel(parent, R.string.hardware_fan);
        final GridLayout fanGrid = new GridLayout(mActivity);
        fanGrid.setColumnCount(4);
        addFanButton(fanGrid, R.string.hardware_system,
                RedmagicHardwareController.FanMode.SYSTEM);
        addFanButton(fanGrid, R.string.hardware_auto,
                RedmagicHardwareController.FanMode.AUTO);
        addFanButton(fanGrid, R.string.state_off,
                RedmagicHardwareController.FanMode.OFF);
        addFanButton(fanGrid, R.string.hardware_level_1,
                RedmagicHardwareController.FanMode.LEVEL_1);
        addFanButton(fanGrid, R.string.hardware_level_2,
                RedmagicHardwareController.FanMode.LEVEL_2);
        addFanButton(fanGrid, R.string.hardware_level_3,
                RedmagicHardwareController.FanMode.LEVEL_3);
        addFanButton(fanGrid, R.string.hardware_level_4,
                RedmagicHardwareController.FanMode.LEVEL_4);
        addFanButton(fanGrid, R.string.hardware_level_5,
                RedmagicHardwareController.FanMode.LEVEL_5);
        parent.addView(fanGrid, matchWidth());

        addLabel(parent, R.string.hardware_pump);
        final GridLayout pumpGrid = new GridLayout(mActivity);
        pumpGrid.setColumnCount(4);
        addPumpButton(pumpGrid, R.string.hardware_system,
                RedmagicHardwareController.PumpMode.SYSTEM);
        addPumpButton(pumpGrid, R.string.state_off,
                RedmagicHardwareController.PumpMode.OFF);
        addPumpButton(pumpGrid, R.string.hardware_slow,
                RedmagicHardwareController.PumpMode.SLOW);
        addPumpButton(pumpGrid, R.string.hardware_medium,
                RedmagicHardwareController.PumpMode.MEDIUM);
        addPumpButton(pumpGrid, R.string.hardware_fast,
                RedmagicHardwareController.PumpMode.FAST);
        parent.addView(pumpGrid, matchWidth());

        update(RedmagicHardwareController.snapshot());
    }

    @Override
    public void onHardwareStateChanged(
            final RedmagicHardwareSnapshot snapshot) {
        update(snapshot);
        mActivity.taskbar().updateHardware(snapshot);
    }

    private void update(final RedmagicHardwareSnapshot snapshot) {
        if (mStatus == null) {
            return;
        }
        final boolean allowed = RuntimeAccess.has(
                RuntimeAccess.Capability.HARDWARE_CONTROL);
        final boolean available = allowed && snapshot.isAvailable();
        if (!available) {
            mStatus.setText(allowed
                    ? R.string.hardware_unavailable
                    : R.string.hardware_root_required);
        } else {
            mStatus.setText(mActivity.getString(
                    R.string.hardware_status,
                    temperature(snapshot.cpuMilliCelsius),
                    temperature(snapshot.gpuMilliCelsius),
                    temperature(snapshot.skinMilliCelsius),
                    temperature(snapshot.batteryMilliCelsius),
                    value(snapshot.fanRpm),
                    pumpStatus(snapshot)));
        }
        updateButtons(
                mFanButtons,
                RedmagicHardwareController.fanMode(),
                available && snapshot.fanAvailable);
        updateButtons(
                mPumpButtons,
                RedmagicHardwareController.pumpMode(),
                available && snapshot.pumpAvailable);
    }

    private void addFanButton(
            final GridLayout grid,
            final int textResId,
            final RedmagicHardwareController.FanMode mode) {
        final Button button = createModeButton(textResId);
        button.setOnClickListener(view -> {
            setEnabled(mFanButtons, false);
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
        });
        mFanButtons.put(mode, button);
        grid.addView(button, gridParams());
    }

    private void addPumpButton(
            final GridLayout grid,
            final int textResId,
            final RedmagicHardwareController.PumpMode mode) {
        final Button button = createModeButton(textResId);
        button.setOnClickListener(view -> {
            setEnabled(mPumpButtons, false);
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
        });
        mPumpButtons.put(mode, button);
        grid.addView(button, gridParams());
    }

    private Button createModeButton(final int textResId) {
        final Button button = mUi.actionButton(
                textResId, DesktopUiFactory.COLOR_PANEL_ALT);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(5), dp(2), dp(5), dp(2));
        button.setGravity(Gravity.CENTER);
        return button;
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
        params.setMargins(0, spacing, 0, dp(6));
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
        params.setMargins(0, dp(8), 0, 0);
        parent.addView(label, params);
        return label;
    }

    private GridLayout.LayoutParams gridParams() {
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(BUTTON_HEIGHT_DP);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        return params;
    }

    private static <T extends Enum<T>> void updateButtons(
            final Map<T, Button> buttons,
            final T selected,
            final boolean enabled) {
        for (final Map.Entry<T, Button> entry : buttons.entrySet()) {
            final Button button = entry.getValue();
            button.setEnabled(enabled);
            button.setAlpha(entry.getKey() == selected ? 1f : 0.68f);
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

    private static String value(final int value) {
        return value == RedmagicHardwareSnapshot.UNKNOWN
                ? "--" : Integer.toString(value);
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
