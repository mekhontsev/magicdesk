package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LauncherAppRepository {
    private static final String TAG = "MagicDeskApps";
    private static final int RESIZE_MODE_UNRESIZEABLE = 0;

    private static Field sResizeModeField;
    private static boolean sResizeModeFieldResolved;

    private final Context mContext;
    private final PackageManager mPackageManager;

    LauncherAppRepository(final Context context) {
        mContext = context.getApplicationContext();
        mPackageManager = context.getPackageManager();
    }

    List<AppItem> load(final boolean universalFreeform) {
        final Intent launcherIntent = new Intent(Intent.ACTION_MAIN);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        final List<ResolveInfo> activities =
                mPackageManager.queryIntentActivities(launcherIntent, 0);
        final List<AppItem> result = new ArrayList<>();
        final Set<String> addedPackages = new HashSet<>();
        final String ownPackage = mContext.getPackageName();

        for (final ResolveInfo resolveInfo : activities) {
            if (resolveInfo == null || resolveInfo.activityInfo == null) {
                continue;
            }
            final String packageName = resolveInfo.activityInfo.packageName;
            if (packageName == null
                    || ownPackage.equals(packageName)
                    || !addedPackages.add(packageName)) {
                continue;
            }
            final CharSequence labelChars =
                    resolveInfo.loadLabel(mPackageManager);
            final String label = labelChars == null || labelChars.length() == 0
                    ? packageName : labelChars.toString();
            final Drawable icon = resolveInfo.loadIcon(mPackageManager);
            final ApplicationInfo applicationInfo =
                    resolveInfo.activityInfo.applicationInfo;
            result.add(new AppItem(
                    label,
                    packageName,
                    universalFreeform,
                    fullscreenPreference(resolveInfo.activityInfo, applicationInfo),
                    icon));
        }

        Collections.sort(result, new Comparator<AppItem>() {
            @Override
            public int compare(final AppItem left, final AppItem right) {
                final int labelCompare =
                        left.label.compareToIgnoreCase(right.label);
                return labelCompare != 0
                        ? labelCompare
                        : left.packageName.compareTo(right.packageName);
            }
        });
        return result;
    }

    AppItem findOrLoad(
            final List<AppItem> apps,
            final String packageName,
            final boolean universalFreeform) {
        final AppItem known = find(apps, packageName);
        if (known != null) {
            return known;
        }
        try {
            final ApplicationInfo info =
                    mPackageManager.getApplicationInfo(packageName, 0);
            final ActivityInfo activityInfo =
                    resolveLauncherActivityInfo(packageName);
            final CharSequence label = info.loadLabel(mPackageManager);
            return new AppItem(
                    label == null ? packageName : label.toString(),
                    packageName,
                    universalFreeform,
                    fullscreenPreference(activityInfo, info),
                    info.loadIcon(mPackageManager));
        } catch (PackageManager.NameNotFoundException error) {
            Log.w(TAG, "Task package is not installed: " + packageName, error);
            return null;
        }
    }

    static AppItem find(final List<AppItem> apps, final String packageName) {
        if (apps == null || packageName == null) {
            return null;
        }
        for (final AppItem app : apps) {
            if (packageName.equals(app.packageName)) {
                return app;
            }
        }
        return null;
    }

    private ActivityInfo resolveLauncherActivityInfo(final String packageName) {
        final Intent launchIntent =
                mPackageManager.getLaunchIntentForPackage(packageName);
        if (launchIntent == null || launchIntent.getComponent() == null) {
            return null;
        }
        try {
            return mPackageManager.getActivityInfo(
                    launchIntent.getComponent(), 0);
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
    }

    private static String fullscreenPreference(
            final ActivityInfo activityInfo,
            final ApplicationInfo applicationInfo) {
        if (activityInfo != null
                && (activityInfo.flags & ActivityInfo.FLAG_IMMERSIVE) != 0) {
            return AppItem.FULLSCREEN_REASON_IMMERSIVE;
        }
        final Integer resizeMode = getResizeMode(activityInfo);
        if (resizeMode != null
                && resizeMode.intValue() == RESIZE_MODE_UNRESIZEABLE) {
            return AppItem.FULLSCREEN_REASON_UNRESIZEABLE;
        }
        if (applicationInfo != null
                && applicationInfo.category == ApplicationInfo.CATEGORY_GAME) {
            return AppItem.FULLSCREEN_REASON_GAME;
        }
        return AppItem.FULLSCREEN_REASON_NONE;
    }

    private static Integer getResizeMode(final ActivityInfo activityInfo) {
        if (activityInfo == null) {
            return null;
        }
        final Field field = resolveResizeModeField();
        if (field == null) {
            return null;
        }
        try {
            return Integer.valueOf(field.getInt(activityInfo));
        } catch (IllegalAccessException | RuntimeException error) {
            return null;
        }
    }

    private static synchronized Field resolveResizeModeField() {
        if (sResizeModeFieldResolved) {
            return sResizeModeField;
        }
        sResizeModeFieldResolved = true;
        try {
            final Field field =
                    ActivityInfo.class.getDeclaredField("resizeMode");
            field.setAccessible(true);
            sResizeModeField = field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG,
                    "ActivityInfo.resizeMode unavailable; using public launch hints");
        }
        return sResizeModeField;
    }
}
