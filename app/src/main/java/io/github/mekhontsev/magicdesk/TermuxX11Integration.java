package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.pm.PackageManager;

import org.json.JSONException;
import org.json.JSONObject;

final class TermuxX11Integration {
    static final String PACKAGE_NAME = "com.termux.x11";

    private TermuxX11Integration() {
    }

    static boolean isInstalled(final Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE_NAME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    static boolean isAvailable(final Context context) {
        return TermuxIntegration.isInstalled(context) && isInstalled(context);
    }

    static String diagnostics(final Context context) {
        return TermuxX11RuntimeStatus.refreshBlocking(
                context, TaskRepository.loadAllNow()).reportLine();
    }

    static JSONObject cachedStatusJson(
            final Context context,
            final TaskRepository.Snapshot tasks) throws JSONException {
        return TermuxX11RuntimeStatus.cached(context, tasks).toJson();
    }

    static JSONObject refreshedStatusJson(
            final Context context,
            final TaskRepository.Snapshot tasks) throws JSONException {
        return TermuxX11RuntimeStatus.refreshBlocking(
                context, tasks).toJson();
    }
}
