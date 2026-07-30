package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;

import java.lang.ref.WeakReference;

public final class ConsoleSeedActivity extends Activity {
    private static WeakReference<ConsoleSeedActivity> sActive =
            new WeakReference<>(null);

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        synchronized (ConsoleSeedActivity.class) {
            sActive = new WeakReference<>(this);
        }
        final View background = new View(this);
        background.setBackgroundColor(Color.BLACK);
        setContentView(background);
    }

    @Override
    protected void onDestroy() {
        synchronized (ConsoleSeedActivity.class) {
            if (sActive.get() == this) {
                sActive.clear();
            }
        }
        super.onDestroy();
    }

    static void finishActive() {
        final ConsoleSeedActivity activity;
        synchronized (ConsoleSeedActivity.class) {
            activity = sActive.get();
        }
        if (activity != null) {
            activity.runOnUiThread(() -> {
                activity.finishAndRemoveTask();
            });
        }
    }
}
