package io.github.mekhontsev.magicdesk;

import android.inputmethodservice.InputMethodService;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/** Debug-only keyboard peer: exercises Android's real IME transport without a third-party keyboard. */
public final class HostedFixtureIme extends InputMethodService {
    static HostedFixtureIme active;
    static Runnable changed;

    @Override public View onCreateInputView() {
        var view = new android.widget.TextView(this);
        view.setText("MagicDesk IME verification");
        view.setTextColor(android.graphics.Color.WHITE);
        view.setBackgroundColor(0xff333333);
        view.setGravity(android.view.Gravity.CENTER);
        view.setMinimumHeight(Math.round(120 * getResources().getDisplayMetrics().density));
        return view;
    }
    @Override public boolean onEvaluateInputViewShown() {
        super.onEvaluateInputViewShown();
        return true;
    }
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        active = this;
        if (changed != null) changed.run();
    }
    @Override public void onFinishInput() {
        super.onFinishInput();
        if (active == this) active = null;
        if (changed != null) changed.run();
    }
    @Override public void onDestroy() {
        if (active == this) active = null;
        super.onDestroy();
    }
    static InputConnection connection() {
        if (active == null || active.getCurrentInputEditorInfo() == null
                || !BuildConfig.APPLICATION_ID.equals(active.getCurrentInputEditorInfo().packageName)) return null;
        return active.getCurrentInputConnection();
    }
}
