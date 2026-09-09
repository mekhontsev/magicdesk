package io.github.mekhontsev.magicdesk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageButton;
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
    private TextView mVolumeValue;
    private ImageButton mMute;

    DesktopAudioPanelController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
        mAudioManager = activity.getSystemService(AudioManager.class);
    }

    void start() {
        if (mVolumeReceiver != null) {
            return;
        }
        final BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(
                    final Context context, final Intent intent) {
                update();
            }
        };
        mActivity.registerReceiver(
                receiver,
                new IntentFilter(VOLUME_CHANGED_ACTION));
        mVolumeReceiver = receiver;
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
        mUi.addControlSection(parent, R.string.audio_section_title, spacing);

        mRouteStatus = new TextView(mActivity);
        mRouteStatus.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mRouteStatus.setTextSize(13);
        parent.addView(mRouteStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        mMute = mUi.menuIconButton(R.drawable.ic_volume, R.string.audio_mute);
        mMute.setEnabled(mAudioManager != null);
        mMute.setOnClickListener(view -> toggleMute());
        row.addView(mMute, new LinearLayout.LayoutParams(dp(48), dp(48)));

        mVolume = new SeekBar(mActivity);
        mVolume.setContentDescription(mActivity.getString(R.string.audio_volume));
        mVolume.setEnabled(mAudioManager != null);
        mVolume.setSplitTrack(false);
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
        row.addView(mVolume, new LinearLayout.LayoutParams(0, dp(48), 1));
        mVolumeValue = new TextView(mActivity);
        mVolumeValue.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mVolumeValue.setTextSize(12);
        mVolumeValue.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.addView(mVolumeValue, new LinearLayout.LayoutParams(dp(60), dp(48)));
        parent.addView(row);

        final Button soundSettings = mUi.menuItem(
                R.string.audio_sound_settings, DesktopUiFactory.COLOR_TEXT);
        soundSettings.setTextSize(13);
        soundSettings.setOnClickListener(view -> {
            mActivity.hideAllPanels();
            mActivity.invokeDesktopAction("sound-settings");
        });
        parent.addView(soundSettings, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(40)));
        mActivity.registerAutomationUiElement(mMute, "quick_controls.mute", "button",
                mActivity.getString(R.string.audio_mute));
        mActivity.registerAutomationUiElement(soundSettings,
                "quick_controls.android_sound", "button", mActivity.getString(R.string.audio_sound_settings));
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
            mMute.setImageResource(muted ? R.drawable.ic_volume_off : R.drawable.ic_volume);
            final String action = mActivity.getString(muted
                    ? R.string.audio_unmute : R.string.audio_mute);
            mMute.setContentDescription(action);
            mMute.setTooltipText(action);
            mActivity.registerAutomationUiElement(mMute, "quick_controls.mute", "button", action);
        }
        if (mVolumeValue != null) {
            mVolumeValue.setText(mActivity.getString(R.string.audio_volume_value,
                    volume, mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
        }
        if (mRouteStatus != null) {
            mRouteStatus.setText(mActivity.getString(
                    R.string.audio_status,
                    currentOutputName()));
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

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
