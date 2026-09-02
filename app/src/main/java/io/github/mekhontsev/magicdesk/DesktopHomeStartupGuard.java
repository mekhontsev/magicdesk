package io.github.mekhontsev.magicdesk;

import android.app.Application;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
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
        if (roles == null
                || !roles.isRoleAvailable(RoleManager.ROLE_HOME)
                || !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            return false;
        }

        final DesktopHomeRoleLease.State lease =
                DesktopHomeRoleLease.snapshot();
        try {
            DesktopHomeRoleLease.prepareEmergencyRelease();
        } catch (IOException error) {
            Log.w(TAG, "could not mark HOME lease for emergency release", error);
        }

        try {
            setHomeEligibility(
                    context,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED);
        } catch (RuntimeException error) {
            Log.e(TAG, "could not disable MagicDesk HOME", error);
            return false;
        }
        sRelinquishedOnProcessStart = true;

        try {
            context.startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(HOME_FLAGS));
            Log.w(TAG, "relinquished stale HOME at process start"
                    + (lease == null ? " without a stored lease" : ""));
            return true;
        } catch (RuntimeException error) {
            Log.e(TAG, "could not open the system HOME resolver", error);
            return false;
        }
    }

    static void enableHomeEligibility(final Context context) {
        setHomeEligibility(
                context,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
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

    private static void setHomeEligibility(
            final Context context,
            final int state) {
        final ComponentName component = new ComponentName(
                context,
                PhoneHomeActivity.class);
        context.getPackageManager().setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP);
    }
}
