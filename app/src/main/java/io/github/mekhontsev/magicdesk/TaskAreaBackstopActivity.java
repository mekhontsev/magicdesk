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
    private static final String EXTRA_PASSIVE_INPUT =
            BuildConfig.APPLICATION_ID + ".extra.PASSIVE_BACKSTOP_INPUT";
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
                .putExtra(
                        EXTRA_PASSIVE_INPUT,
                        instanceKey.startsWith("session:"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static boolean isBackstopComponent(final ComponentName component) {
        return COMPONENT.equals(component);
    }

    static TaskAreaBackstopRole getBackstopRole(final Intent intent) {
        if (intent == null || intent.getData() == null) {
            return TaskAreaBackstopRole.UNKNOWN;
        }
        final Uri data = intent.getData();
        if (!"magicdesk-task-area-backstop".equals(data.getScheme())) {
            return TaskAreaBackstopRole.UNKNOWN;
        }
        final String instanceKey = Uri.decode(
                data.getEncodedSchemeSpecificPart());
        if (instanceKey.startsWith("session:")) {
            return TaskAreaBackstopRole.SESSION;
        }
        if (instanceKey.startsWith("fullscreen-slot:")) {
            return TaskAreaBackstopRole.FULLSCREEN;
        }
        return TaskAreaBackstopRole.UNKNOWN;
    }

    static boolean isBackstopTask(final TaskRepository.TaskEntry task) {
        if (task == null
                || !BuildConfig.APPLICATION_ID.equals(task.packageName)) {
            return false;
        }
        return isBackstopComponentName(task.componentName)
                || isBackstopComponentName(task.topActivityName);
    }

    static boolean isBackstopComponentName(final String componentName) {
        return (BuildConfig.APPLICATION_ID + "/" + CLASS_NAME)
                        .equals(componentName)
                || (BuildConfig.APPLICATION_ID
                        + "/.TaskAreaBackstopActivity")
                        .equals(componentName);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int windowFlags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        if (getIntent().getBooleanExtra(EXTRA_PASSIVE_INPUT, false)) {
            // The session backstop has a real desktop owner below or above it,
            // so it must never retain keyboard focus. A fullscreen-slot anchor
            // stays focusable at the brief child-removal boundary to avoid
            // leaving its still-focusable plane without an input target before
            // ShellFullscreenTaskPlanes parks the plane.
            windowFlags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        getWindow().addFlags(windowFlags);
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
