package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.widget.Button;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;

/** Deterministic window used only by the manually started desktop self-test. */
public final class DesktopSelfTestActivity extends Activity {
    static final String EXTRA_DISPLAY_ID = "self_test_display_id";
    static final String EXTRA_TOKEN = "self_test_token";
    static final String EXTRA_ALLOW_DISPLAY_MOVE = "self_test_allow_display_move";
    static final String MARKER_FILE = "desktop-self-test-input.txt";
    private static volatile WeakReference<DesktopSelfTestActivity> sActive =
            new WeakReference<>(null);

    private int mExpectedDisplayId = Display.INVALID_DISPLAY;
    private String mToken = "";
    private boolean mAllowDisplayMove;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sActive = new WeakReference<>(this);
        mExpectedDisplayId = getIntent().getIntExtra(
                EXTRA_DISPLAY_ID, Display.INVALID_DISPLAY);
        mAllowDisplayMove = getIntent().getBooleanExtra(
                EXTRA_ALLOW_DISPLAY_MOVE, false);
        mToken = getIntent().getStringExtra(EXTRA_TOKEN);
        if (mToken == null) {
            mToken = "";
        }

        final Button target = new Button(this);
        target.setAllCaps(false);
        target.setGravity(Gravity.CENTER);
        target.setText(R.string.self_test_window_target);
        target.setTextColor(Color.WHITE);
        target.setTextSize(18);
        target.setBackgroundColor(0xFF123A4A);
        target.setOnClickListener(view -> writeInputMarker());
        setContentView(target);
        finishIfMoved();
    }

    @Override
    protected void onResume() {
        super.onResume();
        finishIfMoved();
    }

    @Override
    protected void onDestroy() {
        final DesktopSelfTestActivity active = sActive.get();
        if (active == this) {
            sActive.clear();
        }
        super.onDestroy();
    }

    static boolean finishActiveTask() {
        final DesktopSelfTestActivity activity = sActive.get();
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(() -> {
            activity.finishAndRemoveTask();
            activity.overridePendingTransition(0, 0);
        });
        return true;
    }

    private void finishIfMoved() {
        if (mAllowDisplayMove) {
            return;
        }
        final Display display = getDisplay();
        final int displayId = display == null
                ? Display.INVALID_DISPLAY : display.getDisplayId();
        if (mExpectedDisplayId <= Display.DEFAULT_DISPLAY
                || displayId == mExpectedDisplayId) {
            return;
        }
        finishAndRemoveTask();
        overridePendingTransition(0, 0);
    }

    private void writeInputMarker() {
        final Display display = getDisplay();
        final int displayId = display == null
                ? Display.INVALID_DISPLAY : display.getDisplayId();
        final String value = mToken + "|" + displayId;
        try (FileOutputStream output = openFileOutput(
                MARKER_FILE, MODE_PRIVATE)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The controller reports a missing marker as the input test failure.
        }
    }
}
