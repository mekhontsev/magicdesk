package io.github.mekhontsev.magicdesk;

import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;

final class PointerSpeedPanelController {
    private static final String POINTER_SPEED = "pointer_speed";
    private static final int MIN_SPEED = -7;
    private static final int MAX_SPEED = 7;

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private ContentObserver mObserver;
    private SeekBar mSlider;
    private TextView mValue;
    private boolean mTracking;
    private boolean mWritePending;

    PointerSpeedPanelController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    void start() {
        if (mObserver != null) {
            return;
        }
        mObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(final boolean selfChange) {
                refresh();
            }
        };
        mActivity.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(POINTER_SPEED),
                false,
                mObserver);
    }

    void stop() {
        if (mObserver == null) {
            return;
        }
        mActivity.getContentResolver().unregisterContentObserver(mObserver);
        mObserver = null;
    }

    void populate(final LinearLayout parent, final int spacing) {
        final TextView title = mUi.sectionTitle(
                R.string.hardware_input_section);
        final LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, spacing, 0, 0);
        parent.addView(title, titleParams);

        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final TextView label = new TextView(mActivity);
        label.setText(R.string.pointer_speed);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(13);
        row.addView(label, new LinearLayout.LayoutParams(
                dp(108), LinearLayout.LayoutParams.WRAP_CONTENT));

        mSlider = new SeekBar(mActivity);
        mSlider.setMin(MIN_SPEED);
        mSlider.setMax(MAX_SPEED);
        mSlider.setKeyProgressIncrement(1);
        mSlider.setSplitTrack(false);
        mSlider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            final SeekBar seekBar,
                            final int progress,
                            final boolean fromUser) {
                        updateValue(progress);
                    }

                    @Override
                    public void onStartTrackingTouch(final SeekBar seekBar) {
                        mTracking = true;
                    }

                    @Override
                    public void onStopTrackingTouch(final SeekBar seekBar) {
                        mTracking = false;
                        apply(seekBar.getProgress());
                    }
                });
        row.addView(mSlider, new LinearLayout.LayoutParams(
                0, dp(40), 1));

        mValue = new TextView(mActivity);
        mValue.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mValue.setTextSize(13);
        mValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(mValue, new LinearLayout.LayoutParams(
                dp(32), LinearLayout.LayoutParams.WRAP_CONTENT));

        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        refresh();
    }

    void refresh() {
        if (mSlider == null) {
            return;
        }
        if (!mTracking && !mWritePending) {
            mSlider.setProgress(readSpeed());
        }
        mSlider.setEnabled(ShellAccess.isReady() && !mWritePending);
        updateValue(mSlider.getProgress());
    }

    private void apply(final int speed) {
        if (!ShellAccess.isReady() || mWritePending) {
            refresh();
            return;
        }
        final int value = clamp(speed);
        if (value == readSpeed()) {
            return;
        }
        mWritePending = true;
        refresh();
        DesktopOperations.executeSerialized(() -> {
            boolean success = false;
            String detail = "";
            try {
                ShellAccess.run(
                        "/system/bin/settings put system "
                                + POINTER_SPEED + " " + value);
                success = true;
            } catch (IOException error) {
                detail = error.getMessage();
            }
            final boolean applied = success;
            final String failureDetail = detail;
            mActivity.runOnUiThread(() -> {
                if (mActivity.isActivityUnavailable()) {
                    return;
                }
                mWritePending = false;
                refresh();
                if (!applied) {
                    mActivity.setErrorStatus(
                            "INPUT-POINTER-001",
                            mActivity.getString(
                                    R.string.status_pointer_speed_failed)
                                    + (failureDetail == null
                                            || failureDetail.isEmpty()
                                            ? "" : ": " + failureDetail));
                }
            });
        });
    }

    private int readSpeed() {
        return clamp(Settings.System.getInt(
                mActivity.getContentResolver(), POINTER_SPEED, 0));
    }

    private void updateValue(final int speed) {
        if (mValue != null) {
            mValue.setText(mActivity.getString(
                    R.string.pointer_speed_value, Integer.valueOf(speed)));
        }
    }

    private static int clamp(final int speed) {
        return Math.max(MIN_SPEED, Math.min(MAX_SPEED, speed));
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
