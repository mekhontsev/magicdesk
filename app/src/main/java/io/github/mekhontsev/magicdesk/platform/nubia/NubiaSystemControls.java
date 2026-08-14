package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.DesktopShellActivity;
import io.github.mekhontsev.magicdesk.DesktopUiFactory;
import io.github.mekhontsev.magicdesk.PlatformSystemControls;
import io.github.mekhontsev.magicdesk.R;
import io.github.mekhontsev.magicdesk.ShellAccess;

import android.content.Intent;
import android.os.BatteryManager;
import android.text.TextUtils;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** RedMagic power and cooling controls contributed to the System panel. */
final class NubiaSystemControls implements PlatformSystemControls {
    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final RedmagicHardwarePanelController mHardware;
    private final ChargeSeparationController mChargeSeparation;

    private TextView mBatteryStatus;
    private Switch mChargeSeparationSwitch;
    private Intent mLastBatteryIntent;
    private boolean mUpdatingChargeSeparation;

    NubiaSystemControls(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mHardware = new RedmagicHardwarePanelController(activity, ui);
        mChargeSeparation = new ChargeSeparationController(
                activity, this::updateChargeSeparation);
    }

    @Override
    public void start() {
        mChargeSeparation.start();
        mHardware.start();
    }

    @Override
    public void stop() {
        mChargeSeparation.stop();
        mHardware.stop();
    }

    @Override
    public void setPanelVisible(final boolean visible) {
        mHardware.setMonitoringActive(visible);
    }

    @Override
    public void populate(final LinearLayout parent, final int spacing) {
        final TextView powerTitle = mUi.sectionTitle(
                R.string.hardware_power_section);
        final LinearLayout.LayoutParams powerTitleParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        powerTitleParams.setMargins(0, spacing, 0, 0);
        parent.addView(powerTitle, powerTitleParams);

        mBatteryStatus = new TextView(mActivity);
        mBatteryStatus.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mBatteryStatus.setTextSize(14);
        parent.addView(
                mBatteryStatus,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
        updateBatteryStatus(mLastBatteryIntent);

        mChargeSeparationSwitch = new Switch(mActivity);
        mChargeSeparationSwitch.setText(R.string.charge_separation_label);
        mChargeSeparationSwitch.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mChargeSeparationSwitch.setTextSize(14);
        mChargeSeparationSwitch.setOnCheckedChangeListener(
                (button, checked) -> {
                    if (!mUpdatingChargeSeparation) {
                        setChargeSeparationEnabled(checked);
                    }
                });
        final LinearLayout.LayoutParams chargeParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        chargeParams.setMargins(0, spacing / 2, 0, 0);
        parent.addView(mChargeSeparationSwitch, chargeParams);
        updateChargeSeparation(mChargeSeparation.state());

        mHardware.populate(parent, spacing);
    }

    @Override
    public void onBatteryChanged(final Intent battery) {
        mLastBatteryIntent = battery;
        updateBatteryStatus(battery);
    }

    private void updateBatteryStatus(final Intent battery) {
        if (mBatteryStatus == null) {
            return;
        }
        if (battery == null) {
            mBatteryStatus.setText(R.string.battery_status_unknown);
            return;
        }
        final int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = battery.getIntExtra(
                BatteryManager.EXTRA_SCALE, 100);
        final int percent = level < 0 || scale <= 0
                ? -1
                : Math.max(0, Math.min(
                        100, Math.round(level * 100f / scale)));
        final int status = battery.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        final int stateResId;
        if (mChargeSeparation.state().enabled) {
            stateResId = R.string.battery_state_bypass;
        } else if (status == BatteryManager.BATTERY_STATUS_CHARGING) {
            stateResId = R.string.battery_state_charging;
        } else if (status == BatteryManager.BATTERY_STATUS_FULL) {
            stateResId = R.string.battery_state_full;
        } else {
            stateResId = R.string.battery_state_discharging;
        }
        mBatteryStatus.setText(mActivity.getString(
                R.string.battery_panel_status,
                percent < 0 ? "--%" : percent + "%",
                mActivity.getString(stateResId)));
    }

    private void updateChargeSeparation(
            final ChargeSeparationController.State state) {
        mActivity.updatePlatformChargeSeparation(state.enabled);
        updateBatteryStatus(mLastBatteryIntent);
        if (mChargeSeparationSwitch == null) {
            return;
        }
        mChargeSeparationSwitch.setVisibility(
                state.supported
                        ? android.view.View.VISIBLE
                        : android.view.View.GONE);
        mUpdatingChargeSeparation = true;
        mChargeSeparationSwitch.setChecked(state.enabled);
        mUpdatingChargeSeparation = false;
        mChargeSeparationSwitch.setEnabled(
                !mChargeSeparation.isWritePending() && state.canChange());

        final int descriptionResId;
        if (!ShellAccess.isReady()) {
            descriptionResId = R.string.charge_separation_privileged_required;
        } else if (state.enabled) {
            descriptionResId = R.string.charge_separation_enabled_description;
        } else if (!state.plugged) {
            descriptionResId = R.string.charge_separation_power_required;
        } else if (state.batteryPercent < 20) {
            descriptionResId = R.string.charge_separation_battery_required;
        } else {
            descriptionResId = R.string.charge_separation_disabled_description;
        }
        final String description = mActivity.getString(descriptionResId);
        mChargeSeparationSwitch.setContentDescription(description);
        mChargeSeparationSwitch.setTooltipText(description);
    }

    private void setChargeSeparationEnabled(final boolean enabled) {
        mChargeSeparationSwitch.setEnabled(false);
        mChargeSeparation.setEnabled(
                enabled,
                (success, message) -> {
                    updateChargeSeparation(mChargeSeparation.state());
                    if (success) {
                        mActivity.setStatus(enabled
                                ? R.string.status_charge_separation_enabled
                                : R.string.status_charge_separation_disabled);
                    } else {
                        mActivity.setErrorStatus(
                                "REDMAGIC-CHARGE-001",
                                TextUtils.isEmpty(message)
                                        ? mActivity.getString(
                                                R.string.status_charge_separation_failed)
                                        : message);
                    }
                });
    }
}
