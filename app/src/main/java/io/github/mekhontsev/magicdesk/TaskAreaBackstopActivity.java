package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.window.OnBackInvokedDispatcher;

/** Inert structural task that keeps an organizer-owned task area non-empty. */
public final class TaskAreaBackstopActivity extends Activity {
    private static final String CLASS_NAME =
            BuildConfig.APPLICATION_ID + ".TaskAreaBackstopActivity";
    static final ComponentName COMPONENT = new ComponentName(
            BuildConfig.APPLICATION_ID,
            CLASS_NAME);

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

    static boolean isBackstopTask(final TaskRepository.TaskEntry task) {
        if (task == null
                || !BuildConfig.APPLICATION_ID.equals(task.packageName)) {
            return false;
        }
        final String componentName = task.componentName;
        return (BuildConfig.APPLICATION_ID + "/" + CLASS_NAME)
                        .equals(componentName)
                || (BuildConfig.APPLICATION_ID
                        + "/.TaskAreaBackstopActivity")
                        .equals(componentName);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // WindowManager may select the anchor between removal of the client
        // task and the callback that parks the whole plane. Keep a valid
        // focusable input channel during that boundary to avoid an input ANR;
        // the window never accepts pointer input, and an idle plane itself is
        // made non-focusable by ShellFullscreenTaskPlanes.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> { });
        final View content = new View(this);
        content.setBackgroundColor(Color.TRANSPARENT);
        content.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setContentView(content);
    }
}
