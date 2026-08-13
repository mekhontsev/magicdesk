package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Display;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Deterministic window used only by the manually started desktop self-test. */
public final class DesktopSelfTestActivity extends Activity {
    static final String EXTRA_DISPLAY_ID = "self_test_display_id";
    static final String EXTRA_TOKEN = "self_test_token";
    static final String EXTRA_ALLOW_DISPLAY_MOVE = "self_test_allow_display_move";
    static final String FIRST_FRAME_MARKER_FILE =
            "desktop-self-test-first-frame.txt";
    static final String TEXT_MARKER_FILE = "desktop-self-test-text.txt";
    private int mExpectedDisplayId = Display.INVALID_DISPLAY;
    private String mToken = "";
    private boolean mAllowDisplayMove;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mExpectedDisplayId = getIntent().getIntExtra(
                EXTRA_DISPLAY_ID, Display.INVALID_DISPLAY);
        mAllowDisplayMove = getIntent().getBooleanExtra(
                EXTRA_ALLOW_DISPLAY_MOVE, false);
        mToken = getIntent().getStringExtra(EXTRA_TOKEN);
        if (mToken == null) {
            mToken = "";
        }

        final FrameLayout content = createContent();
        recordFirstFrame(content);
        setContentView(content);
        finishIfMoved();
    }

    @Override
    protected void onResume() {
        super.onResume();
        finishIfMoved();
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

    private FrameLayout createContent() {
        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF123A4A);
        root.setFocusableInTouchMode(true);

        final EditText input = new EditText(this);
        input.setGravity(Gravity.CENTER);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setSingleLine(true);
        input.setShowSoftInputOnFocus(false);
        input.setTextColor(Color.WHITE);
        input.setTextSize(22);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(
                    final CharSequence text,
                    final int start,
                    final int count,
                    final int after) {
            }

            @Override
            public void onTextChanged(
                    final CharSequence text,
                    final int start,
                    final int before,
                    final int count) {
                if (count > 0) {
                    writeTextMarker(text.subSequence(start, start + count));
                }
            }

            @Override
            public void afterTextChanged(final Editable text) {
            }
        });
        final FrameLayout.LayoutParams inputLayout =
                new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
        inputLayout.setMargins(48, 48, 48, 48);
        root.addView(input, inputLayout);
        // Keep a deterministic editor focused inside each fixture. The
        // desktop taskbar can focus a window, but it must not guess which
        // child view an application wants to receive keyboard input.
        input.requestFocus();
        return root;
    }

    private void recordFirstFrame(final FrameLayout content) {
        content.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        final ViewTreeObserver observer =
                                content.getViewTreeObserver();
                        if (observer.isAlive()) {
                            observer.removeOnPreDrawListener(this);
                        }
                        writeMarker(FIRST_FRAME_MARKER_FILE,
                                mToken + "|" + displayId() + "|"
                                        + (isInMultiWindowMode()
                                                ? "freeform" : "fullscreen"));
                        return true;
                    }
                });
    }

    private void writeTextMarker(final CharSequence inserted) {
        writeMarker(TEXT_MARKER_FILE,
                mToken + "|" + displayId() + "|" + inserted);
    }

    private int displayId() {
        final Display display = getDisplay();
        return display == null
                ? Display.INVALID_DISPLAY : display.getDisplayId();
    }

    private void writeMarker(final String fileName, final String value) {
        try (FileOutputStream output = openFileOutput(
                fileName, MODE_PRIVATE)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // The controller reports a missing marker as the input test failure.
        }
    }
}
