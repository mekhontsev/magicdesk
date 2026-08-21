package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;

/** Inert structural task that keeps an organizer-owned task area non-empty. */
public final class TaskAreaBackstopActivity extends Activity {
    static final ComponentName COMPONENT = new ComponentName(
            BuildConfig.APPLICATION_ID,
            TaskAreaBackstopActivity.class.getName());

    static Intent createIntent(final String instanceKey) {
        if (instanceKey == null || instanceKey.isEmpty()) {
            throw new IllegalArgumentException("backstop instance is required");
        }
        return new Intent()
                .setComponent(COMPONENT)
                .setData(Uri.parse("magicdesk-task-area-backstop:"
                        + Uri.encode(instanceKey)))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static boolean isBackstopComponent(final ComponentName component) {
        return COMPONENT.equals(component);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        final View content = new View(this);
        content.setBackgroundColor(Color.TRANSPARENT);
        content.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setContentView(content);
    }
}
