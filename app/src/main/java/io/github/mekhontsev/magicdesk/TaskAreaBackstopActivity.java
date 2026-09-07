package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.window.OnBackInvokedDispatcher;

/** Inert structural task that keeps an organizer-owned task area non-empty. */
public final class TaskAreaBackstopActivity extends Activity {
    private static final String TAG = "MagicDeskBackstop";
    private static final String EXTRA_INPUT_POLICY =
            BuildConfig.APPLICATION_ID + ".extra.BACKSTOP_INPUT_POLICY";
    private static final String CLASS_NAME =
            BuildConfig.APPLICATION_ID + ".TaskAreaBackstopActivity";
    static final ComponentName COMPONENT = new ComponentName(
            BuildConfig.APPLICATION_ID,
            CLASS_NAME);

    static Intent createIntent(final String instanceKey) {
        if (instanceKey == null || instanceKey.isEmpty()) {
            throw new IllegalArgumentException("backstop instance is required");
        }
        // This factory runs in the shell process. Keep the privileged operation
        // there rather than binding another runtime in the anchor's process.
        final Bundle extras = new Bundle();
        extras.putBinder(EXTRA_INPUT_POLICY, new IActivityInputPolicy.Stub() {
            @Override
            public void disableInputSink(final IBinder activityToken) {
                FrameworkActivityInputApi.setRecordInputSinkEnabled(
                        activityToken, false);
            }
        }.asBinder());
        return new Intent()
                .setComponent(COMPONENT)
                .putExtras(extras)
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
        if (instanceKey.startsWith("fullscreen-slot:")) {
            return TaskAreaBackstopRole.FULLSCREEN;
        }
        return TaskAreaBackstopRole.UNKNOWN;
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
        try {
            final Bundle extras = getIntent().getExtras();
            final IActivityInputPolicy inputPolicy = IActivityInputPolicy.Stub.asInterface(
                    extras == null ? null : extras.getBinder(EXTRA_INPUT_POLICY));
            if (inputPolicy == null) {
                throw new IllegalStateException("missing anchor input policy");
            }
            inputPolicy.disableInputSink(
                    FrameworkActivityInputApi.requireActivityToken(this));
        } catch (RemoteException | RuntimeException error) {
            Log.e(TAG, "cannot configure anchor input passthrough", error);
            finishAndRemoveTask();
            return;
        }
        // Retain a real structural window, but accept no pointer input. Its
        // separate ActivityRecord input sink must also be disabled above.
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
