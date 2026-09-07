package io.github.mekhontsev.magicdesk;

import android.app.Application;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.io.IOException;

/** Relinquishes a HOME role that survived the process which acquired it. */
final class DesktopHomeStartupGuard {
    private static final String TAG = "MagicDeskHomeStartup";
    private static final int HOME_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;
    private static boolean sRelinquishedOnProcessStart;

    private DesktopHomeStartupGuard() {
    }

    static boolean relinquishStaleHome(final Context context) {
        if (context == null || !isPrimaryProcess(
                Application.getProcessName(),
                context.getApplicationInfo().processName)) {
            return false;
        }
        // Organizer backstops and self-test fixtures use isolated app
        // processes. Their Application.onCreate() is not a MagicDesk runtime
        // restart and must never release the HOME lease owned by the main
        // process.
        final RoleManager roles = context.getSystemService(RoleManager.class);
        final boolean ownsRole = roles != null
                && roles.isRoleAvailable(RoleManager.ROLE_HOME)
                && roles.isRoleHeld(RoleManager.ROLE_HOME);
        final DesktopHomeRoleLease.State lease =
                DesktopHomeRoleLease.snapshot();
        final Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(HOME_FLAGS);
        final ResolveInfo home = context.getPackageManager().resolveActivity(
                homeIntent, 0);
        final boolean resolvesToUs = home != null && home.activityInfo != null
                && context.getPackageName().equals(home.activityInfo.packageName);
        try {
            // There cannot be a live session before the primary process starts.
            // Role absence alone does not prove our HOME components are inactive.
            DesktopHomeSurfaceRouter.disableHomeSurfaces();
        } catch (IOException error) {
            Log.e(TAG, "could not disable MagicDesk HOME surfaces", error);
            return false;
        }
        if (!ownsRole && lease == null && !resolvesToUs) {
            return false;
        }
        // Startup while holding HOME means the owning desktop process was
        // lost. Disabled surfaces already make MagicDesk ineligible as HOME,
        // so there is no shell-backed release transaction left to recover.
        try {
            DesktopHomeRoleLease.discardForStartupRelinquish();
        } catch (IOException error) {
            Log.w(TAG, "could not discard stale HOME lease", error);
        }
        sRelinquishedOnProcessStart = true;

        try {
            context.startActivity(homeIntent);
            Log.w(TAG, "relinquished stale HOME at process start"
                    + (lease == null ? " without a stored lease" : ""));
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "could not open the system HOME resolver", error);
            return false;
        }
    }

    static boolean shouldDiscardStaleHomeLaunch(final Intent intent) {
        return sRelinquishedOnProcessStart
                && intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME);
    }

    static boolean isPrimaryProcess(
            final String processName,
            final String applicationProcessName) {
        return processName != null
                && processName.equals(applicationProcessName);
    }
}
