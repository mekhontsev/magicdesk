package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;

final class TermuxIntegration {
    static final String PACKAGE_NAME = "com.termux";
    static final String RUN_COMMAND_PERMISSION =
            "com.termux.permission.RUN_COMMAND";
    static final int PERMISSION_REQUEST_CODE = 7312;

    private static final String RUN_COMMAND_SERVICE =
            "com.termux.app.RunCommandService";
    private static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    private static final String EXTRA_COMMAND_PATH =
            "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_WORKDIR =
            "com.termux.RUN_COMMAND_WORKDIR";
    private static final String EXTRA_BACKGROUND =
            "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_SESSION_ACTION =
            "com.termux.RUN_COMMAND_SESSION_ACTION";
    private static final String EXTRA_COMMAND_LABEL =
            "com.termux.RUN_COMMAND_COMMAND_LABEL";

    private TermuxIntegration() {
    }

    static boolean isInstalled(final Activity activity) {
        try {
            activity.getPackageManager().getPackageInfo(PACKAGE_NAME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    @SuppressLint("SdCardPath")
    static boolean openDirectory(
            final Activity activity, final String absolutePath) {
        if (activity.checkSelfPermission(RUN_COMMAND_PERMISSION)
                != PackageManager.PERMISSION_GRANTED) {
            activity.requestPermissions(
                    new String[]{RUN_COMMAND_PERMISSION},
                    PERMISSION_REQUEST_CODE);
            return false;
        }
        final Intent intent = new Intent(ACTION_RUN_COMMAND)
                .setComponent(new ComponentName(
                        PACKAGE_NAME, RUN_COMMAND_SERVICE))
                // RUN_COMMAND executes inside Termux; this is Termux's
                // documented prefix, not MagicDesk private storage.
                .putExtra(EXTRA_COMMAND_PATH,
                        "/data/data/com.termux/files/usr/bin/bash")
                .putExtra(EXTRA_WORKDIR, absolutePath)
                .putExtra(EXTRA_BACKGROUND, false)
                .putExtra(EXTRA_SESSION_ACTION, "0")
                .putExtra(EXTRA_COMMAND_LABEL, "MagicDesk Files");
        activity.startService(intent);
        return true;
    }
}
