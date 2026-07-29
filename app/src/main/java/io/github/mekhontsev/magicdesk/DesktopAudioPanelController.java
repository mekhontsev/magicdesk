package io.github.mekhontsev.magicdesk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

final class DesktopAudioPanelController {
    private static final String VOLUME_CHANGED_ACTION =
            "android.media.VOLUME_CHANGED_ACTION";

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private final AudioManager mAudioManager;

    private BroadcastReceiver mVolumeReceiver;
    private TextView mRouteStatus;
    private SeekBar mVolume;
    private Button mMute;

    DesktopAudioPanelController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mAudioManager = activity.getSystemService(AudioManager.class);
    }

    void start() {
        mVolumeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(
                    final Context context, final Intent intent) {
                update();
            }
        };
        mActivity.registerReceiver(
                mVolumeReceiver,
                new IntentFilter(VOLUME_CHANGED_ACTION));
    }

    void stop() {
        if (mVolumeReceiver == null) {
            return;
        }
        try {
            mActivity.unregisterReceiver(mVolumeReceiver);
        } catch (IllegalArgumentException ignored) {
            // The activity may have already detached the receiver.
        }
        mVolumeReceiver = null;
    }

    void populate(final LinearLayout parent, final int spacing) {
        final TextView heading = new TextView(mActivity);
        heading.setText(R.string.audio_section_title);
        heading.setTextColor(DesktopUiFactory.COLOR_TEXT);
        heading.setTextSize(14);
        final LinearLayout.LayoutParams headingParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        headingParams.setMargins(0, spacing, 0, dp(6));
        parent.addView(heading, headingParams);

        mRouteStatus = new TextView(mActivity);
        mRouteStatus.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mRouteStatus.setTextSize(13);
        parent.addView(mRouteStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mVolume = new SeekBar(mActivity);
        if (mAudioManager != null) {
            mVolume.setMax(mAudioManager.getStreamMaxVolume(
                    AudioManager.STREAM_MUSIC));
        }
        mVolume.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            final SeekBar seekBar,
                            final int progress,
                            final boolean fromUser) {
                        if (fromUser && mAudioManager != null) {
                            mAudioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    progress, 0);
                            update();
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(
                            final SeekBar seekBar) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            final SeekBar seekBar) {
                    }
                });
        parent.addView(mVolume, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final GridLayout actions = new GridLayout(mActivity);
        actions.setColumnCount(3);
        mMute = actionButton(R.string.audio_mute);
        mMute.setOnClickListener(view -> toggleMute());
        addAction(actions, mMute);

        final Button soundSettings = actionButton(
                R.string.audio_sound_settings);
        soundSettings.setOnClickListener(view -> {
            mActivity.hideAllPanels();
            final Intent intent = new Intent(Settings.ACTION_SOUND_SETTINGS);
            mActivity.startActivity(intent);
        });
        addAction(actions, soundSettings);

        final Button touchpad = actionButton(
                R.string.action_open_touchpad);
        touchpad.setEnabled(RuntimeAccess.has(
                RuntimeAccess.Capability.CONSOLE_CONTROL));
        touchpad.setOnClickListener(view -> {
            mActivity.hideAllPanels();
            ConsoleModeSwitcher.openTouchpad();
        });
        addAction(actions, touchpad);
        parent.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        update();
    }

    private void update() {
        if (mAudioManager == null) {
            if (mRouteStatus != null) {
                mRouteStatus.setText(R.string.audio_unavailable);
            }
            return;
        }
        final int volume = mAudioManager.getStreamVolume(
                AudioManager.STREAM_MUSIC);
        final boolean muted = mAudioManager.isStreamMute(
                AudioManager.STREAM_MUSIC);
        if (mVolume != null) {
            mVolume.setProgress(volume);
            mVolume.setEnabled(true);
        }
        if (mMute != null) {
            mMute.setText(muted
                    ? R.string.audio_unmute : R.string.audio_mute);
        }
        if (mRouteStatus != null) {
            mRouteStatus.setText(mActivity.getString(
                    R.string.audio_status,
                    currentOutputName(),
                    Integer.valueOf(volume),
                    Integer.valueOf(mAudioManager.getStreamMaxVolume(
                            AudioManager.STREAM_MUSIC))));
        }
    }

    private void toggleMute() {
        if (mAudioManager == null) {
            return;
        }
        final boolean muted = mAudioManager.isStreamMute(
                AudioManager.STREAM_MUSIC);
        mAudioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                muted ? AudioManager.ADJUST_UNMUTE : AudioManager.ADJUST_MUTE,
                0);
        update();
    }

    private String currentOutputName() {
        final AudioDeviceInfo[] devices =
                mAudioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        AudioDeviceInfo best = null;
        int bestPriority = Integer.MIN_VALUE;
        for (final AudioDeviceInfo device : devices) {
            final int priority = audioTypePriority(device.getType());
            if (priority > bestPriority) {
                best = device;
                bestPriority = priority;
            }
        }
        if (best == null) {
            return mActivity.getString(R.string.audio_route_unknown);
        }
        if (best.getProductName() != null
                && best.getProductName().length() > 0) {
            return best.getProductName().toString();
        }
        return audioTypeName(best.getType());
    }

    private static int audioTypePriority(final int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC:
                return 4;
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return 3;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return 2;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return 1;
            default:
                return 0;
        }
    }

    private String audioTypeName(final int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC:
                return mActivity.getString(R.string.audio_route_hdmi);
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return mActivity.getString(R.string.audio_route_usb);
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
                return mActivity.getString(R.string.audio_route_bluetooth);
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return mActivity.getString(R.string.audio_route_phone);
            default:
                return mActivity.getString(R.string.audio_route_unknown);
        }
    }

    private Button actionButton(final int textResId) {
        final Button button = mUi.actionButton(
                textResId, DesktopUiFactory.COLOR_PANEL_ALT);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(5), dp(2), dp(5), dp(2));
        return button;
    }

    private void addAction(
            final GridLayout grid, final Button button) {
        final GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = dp(44);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        grid.addView(button, params);
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
