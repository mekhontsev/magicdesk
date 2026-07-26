package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

final class KernelFixesIntegration {
    private static final String PACKAGE_NAME =
            "io.github.mekhontsev.magicdesk.kernel";
    private static final String ACTION_MANAGE_FIXES =
            "io.github.mekhontsev.magicdesk.kernel.action.MANAGE_FIXES";

    private KernelFixesIntegration() {
    }

    static boolean isAvailable(final Context context) {
        final PackageManager packageManager = context.getPackageManager();
        if (packageManager.checkSignatures(
                context.getPackageName(), PACKAGE_NAME)
                != PackageManager.SIGNATURE_MATCH) {
            return false;
        }
        final ResolveInfo activity = packageManager.resolveActivity(
                createIntent(), PackageManager.MATCH_DEFAULT_ONLY);
        return activity != null
                && activity.activityInfo != null
                && PACKAGE_NAME.equals(activity.activityInfo.packageName)
                && activity.activityInfo.enabled;
    }

    static boolean launch(final Activity activity) {
        if (!isAvailable(activity)) {
            return false;
        }
        try {
            activity.startActivity(createIntent());
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    private static Intent createIntent() {
        return new Intent(ACTION_MANAGE_FIXES)
                .setPackage(PACKAGE_NAME)
                .addCategory(Intent.CATEGORY_DEFAULT);
    }
}
