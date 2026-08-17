package io.github.mekhontsev.magicdesk;

import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

final class DisplayCapturePanelController {
    private static final int ACTION_HEIGHT_DP = 48;
    private static final int STEP_BUTTON_SIZE_DP = 40;
    private static final int[] SCALE_OPTIONS = {100, 75, 50};

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final Map<Integer, Button> mScaleButtons = new LinkedHashMap<>();
    private final DisplayRecordingController.Listener mRecordingListener =
            this::updateRecordingState;

    private Button mRecordAction;
    private Button mScreenshotAction;
    private Button mResetAction;
    private SeekBar mBitrateSlider;
    private TextView mBitrateValue;
    private TextView mOutputSize;
    private TextView mStatus;
    private DisplayRecordingSettings.Values mSettings;

    DisplayCapturePanelController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mSettings = DisplayRecordingSettings.load(activity);
    }

    void start() {
        DisplayRecordingController.get().addListener(mRecordingListener);
    }

    void stop() {
        DisplayRecordingController.get().removeListener(mRecordingListener);
    }

    void populate(final LinearLayout parent, final int spacing) {
        mSettings = DisplayRecordingSettings.load(mActivity);
        mScaleButtons.clear();

        addHeader(parent);
        addActions(parent, spacing);
        addResolution(parent, spacing);
        addBitrate(parent, spacing);

        mStatus = new TextView(mActivity);
        mStatus.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mStatus.setTextSize(13);
        final LinearLayout.LayoutParams statusParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(0, spacing, 0, 0);
        parent.addView(mStatus, statusParams);

        updateSettingsUi();
        updateRecordingState(DisplayRecordingController.get().snapshot());
    }

    private void addHeader(final LinearLayout parent) {
        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = mUi.sectionTitle(R.string.section_capture);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        mResetAction = mUi.smallButton(
                R.string.action_reset,
                DesktopUiFactory.COLOR_PANEL_ALT);
        mResetAction.setOnClickListener(view -> resetSettings());
        header.addView(mResetAction, new LinearLayout.LayoutParams(
                dp(88), dp(STEP_BUTTON_SIZE_DP)));
        parent.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addActions(
            final LinearLayout parent,
            final int spacing) {
        final GridLayout actions = new GridLayout(mActivity);
        actions.setColumnCount(2);

        mScreenshotAction = mUi.actionButton(
                R.string.action_screenshot,
                DesktopUiFactory.COLOR_CYAN);
        mScreenshotAction.setOnClickListener(view ->
                mActivity.captureDesktopScreenshot());
        addGridButton(actions, mScreenshotAction, 2);

        mRecordAction = mUi.actionButton(
                R.string.action_record_screen,
                DesktopUiFactory.COLOR_RED);
        mRecordAction.setOnClickListener(view ->
                mActivity.toggleDesktopRecording());
        addGridButton(actions, mRecordAction, 2);

        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, spacing, 0, 0);
        parent.addView(actions, params);
    }

    private void addResolution(
            final LinearLayout parent,
            final int spacing) {
        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView label = new TextView(mActivity);
        label.setText(R.string.recording_resolution);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(14);
        header.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        mOutputSize = new TextView(mActivity);
        mOutputSize.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mOutputSize.setTextSize(13);
        mOutputSize.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(mOutputSize, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final LinearLayout.LayoutParams headerParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, spacing, 0, 0);
        parent.addView(header, headerParams);

        final GridLayout scales = new GridLayout(mActivity);
        scales.setColumnCount(SCALE_OPTIONS.length);
        for (final int scale : SCALE_OPTIONS) {
            final Button button = mUi.actionButton(
                    mActivity.getString(
                            R.string.recording_scale_value,
                            Integer.valueOf(scale)),
                    DesktopUiFactory.COLOR_PANEL_ALT);
            button.setOnClickListener(view -> setScale(scale));
            mScaleButtons.put(Integer.valueOf(scale), button);
            addGridButton(scales, button, SCALE_OPTIONS.length);
        }
        parent.addView(scales, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void addBitrate(
            final LinearLayout parent,
            final int spacing) {
        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView label = new TextView(mActivity);
        label.setText(R.string.recording_bitrate);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(14);
        header.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        mBitrateValue = new TextView(mActivity);
        mBitrateValue.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mBitrateValue.setTextSize(14);
        mBitrateValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        header.addView(mBitrateValue, new LinearLayout.LayoutParams(
                dp(96), LinearLayout.LayoutParams.WRAP_CONTENT));

        final LinearLayout.LayoutParams headerParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.setMargins(0, spacing, 0, 0);
        parent.addView(header, headerParams);

        final LinearLayout adjustment = new LinearLayout(mActivity);
        adjustment.setOrientation(LinearLayout.HORIZONTAL);
        adjustment.setGravity(Gravity.CENTER_VERTICAL);

        final Button decrease = stepButton(
                "-", R.string.action_bitrate_decrease);
        decrease.setOnClickListener(view -> adjustBitrate(-1));
        adjustment.addView(decrease, stepButtonParams());

        mBitrateSlider = new SeekBar(mActivity);
        mBitrateSlider.setMin(DisplayRecordingSettings.MIN_BITRATE_MBPS);
        mBitrateSlider.setMax(DisplayRecordingSettings.MAX_BITRATE_MBPS);
        mBitrateSlider.setKeyProgressIncrement(1);
        mBitrateSlider.setProgress(mSettings.bitrateMbps);
        mBitrateSlider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            final SeekBar seekBar,
                            final int progress,
                            final boolean fromUser) {
                        updateBitrateValue(progress);
                    }

                    @Override
                    public void onStartTrackingTouch(final SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(final SeekBar seekBar) {
                        setBitrate(seekBar.getProgress());
                    }
                });
        adjustment.addView(mBitrateSlider, new LinearLayout.LayoutParams(
                0, dp(STEP_BUTTON_SIZE_DP), 1));

        final Button increase = stepButton(
                "+", R.string.action_bitrate_increase);
        increase.setOnClickListener(view -> adjustBitrate(1));
        adjustment.addView(increase, stepButtonParams());
        parent.addView(adjustment, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void setScale(final int scalePercent) {
        DisplayRecordingSettings.saveScale(mActivity, scalePercent);
        mSettings = DisplayRecordingSettings.load(mActivity);
        updateSettingsUi();
    }

    private void setBitrate(final int bitrateMbps) {
        DisplayRecordingSettings.saveBitrate(mActivity, bitrateMbps);
        mSettings = DisplayRecordingSettings.load(mActivity);
        updateSettingsUi();
    }

    private void adjustBitrate(final int delta) {
        setBitrate(mSettings.bitrateMbps + delta);
    }

    private void resetSettings() {
        DisplayRecordingSettings.reset(mActivity);
        mSettings = DisplayRecordingSettings.load(mActivity);
        updateSettingsUi();
    }

    private void updateSettingsUi() {
        for (final Map.Entry<Integer, Button> entry
                : mScaleButtons.entrySet()) {
            final boolean selected = entry.getKey().intValue()
                    == mSettings.scalePercent;
            entry.getValue().setBackground(mUi.rounded(
                    DesktopUiFactory.COLOR_PANEL_ALT,
                    dp(10),
                    selected
                            ? DesktopUiFactory.COLOR_CYAN
                            : DesktopUiFactory.COLOR_PANEL_ALT));
        }
        if (mBitrateSlider != null
                && mBitrateSlider.getProgress() != mSettings.bitrateMbps) {
            mBitrateSlider.setProgress(mSettings.bitrateMbps);
        }
        updateBitrateValue(mSettings.bitrateMbps);
        updateOutputSize();
    }

    private void updateOutputSize() {
        if (mOutputSize == null) {
            return;
        }
        final DisplayRecordingSettings.Dimensions source =
                currentDisplayDimensions();
        if (source == null) {
            mOutputSize.setText(R.string.recording_display_unavailable);
            return;
        }
        final DisplayRecordingSettings.Dimensions output =
                DisplayRecordingSettings.scaledDimensions(
                        source.width,
                        source.height,
                        mSettings.scalePercent);
        mOutputSize.setText(mActivity.getString(
                R.string.recording_output_size,
                Integer.valueOf(output.width),
                Integer.valueOf(output.height)));
    }

    private void updateBitrateValue(final int bitrateMbps) {
        if (mBitrateValue != null) {
            mBitrateValue.setText(mActivity.getString(
                    R.string.recording_bitrate_value,
                    Integer.valueOf(bitrateMbps)));
        }
    }

    private void updateRecordingState(
            final DisplayRecordingController.Snapshot snapshot) {
        if (mRecordAction == null) {
            return;
        }
        final int label;
        switch (snapshot.state) {
            case STARTING:
                label = R.string.action_recording_starting;
                break;
            case RECORDING:
                label = R.string.action_stop_recording;
                break;
            case FINALIZING:
                label = R.string.action_recording_finalizing;
                break;
            case IDLE:
            default:
                label = R.string.action_record_screen;
                break;
        }
        mRecordAction.setText(label);
        mRecordAction.setEnabled(
                ShellAccess.isReady()
                        && (snapshot.state == DisplayRecordingController.State.IDLE
                        || snapshot.state
                                == DisplayRecordingController.State.RECORDING));
        final boolean settingsEnabled =
                ShellAccess.isReady()
                        && snapshot.state
                                == DisplayRecordingController.State.IDLE;
        if (mScreenshotAction != null) {
            mScreenshotAction.setEnabled(ShellAccess.isReady());
        }
        if (mResetAction != null) {
            mResetAction.setEnabled(settingsEnabled);
        }
        if (mBitrateSlider != null) {
            mBitrateSlider.setEnabled(settingsEnabled);
        }
        for (final Button button : mScaleButtons.values()) {
            button.setEnabled(settingsEnabled);
        }
        if (mStatus != null) {
            mStatus.setText(snapshot.message);
        }
    }

    private Button stepButton(
            final String text,
            final int descriptionResId) {
        final Button button = mUi.smallButton(
                text, DesktopUiFactory.COLOR_PANEL_ALT);
        button.setTextSize(16);
        button.setContentDescription(mActivity.getString(descriptionResId));
        button.setTooltipText(mActivity.getString(descriptionResId));
        return button;
    }

    private LinearLayout.LayoutParams stepButtonParams() {
        final LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        dp(STEP_BUTTON_SIZE_DP),
                        dp(STEP_BUTTON_SIZE_DP));
        params.setMargins(dp(2), 0, dp(2), 0);
        return params;
    }

    private void addGridButton(
            final GridLayout grid,
            final Button button,
            final int columnCount) {
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(ACTION_HEIGHT_DP);
        params.columnSpec = GridLayout.spec(
                GridLayout.UNDEFINED, 1f / columnCount);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(button, params);
    }

    private DisplayRecordingSettings.Dimensions currentDisplayDimensions() {
        final DisplayMetrics metrics = mActivity.getResources()
                .getDisplayMetrics();
        return metrics.widthPixels > 0 && metrics.heightPixels > 0
                ? new DisplayRecordingSettings.Dimensions(
                        metrics.widthPixels, metrics.heightPixels)
                : null;
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
