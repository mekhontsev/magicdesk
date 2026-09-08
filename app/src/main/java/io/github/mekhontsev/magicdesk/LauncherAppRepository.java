package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LauncherAppRepository {
    private static final String TAG = "MagicDeskApps";
    private static final int RESIZE_MODE_UNRESIZEABLE = 0;

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final LauncherApps mLauncherApps;
    private final LauncherIconRenderer mIconRenderer;
    private final AppProfile mProfile;

    LauncherAppRepository(final Context context) {
        mContext = context.getApplicationContext();
        mPackageManager = context.getPackageManager();
        mLauncherApps = context.getSystemService(LauncherApps.class);
        mIconRenderer = new LauncherIconRenderer(context.getResources());
        mProfile = AppProfile.current(context);
    }

    List<AppItem> load(final boolean universalFreeform) {
        final List<LauncherActivityInfo> activities = mLauncherApps == null
                ? Collections.<LauncherActivityInfo>emptyList()
                : mLauncherApps.getActivityList(null, Process.myUserHandle());
        final List<AppItem> result = new ArrayList<>();
        final Set<AppIdentity> addedPackages = new HashSet<>();
        final String ownPackage = mContext.getPackageName();

        for (final LauncherActivityInfo launcherInfo : activities) {
            if (launcherInfo == null || launcherInfo.getComponentName() == null) {
                continue;
            }
            if (!Process.myUserHandle().equals(launcherInfo.getUser())) {
                continue;
            }
            final String packageName =
                    launcherInfo.getComponentName().getPackageName();
            if (packageName == null
                    || ownPackage.equals(packageName)
                    || !addedPackages.add(mProfile.application(packageName))) {
                continue;
            }
            final CharSequence labelChars =
                    launcherInfo.getLabel();
            final String label = labelChars == null || labelChars.length() == 0
                    ? packageName : labelChars.toString();
            final ApplicationInfo applicationInfo =
                    launcherInfo.getApplicationInfo();
            final ActivityInfo activityInfo =
                    launcherInfo.getActivityInfo();
            final Drawable icon = loadIcon(activityInfo, applicationInfo);
            result.add(new AppItem(
                    mProfile,
                    label,
                    packageName,
                    universalFreeform,
                    fullscreenPreference(activityInfo, applicationInfo),
                    icon,
                    AppLaunchTarget.packageDefault(packageName)));
        }

        addMagicDeskEntryPoints(result, addedPackages, universalFreeform);
        addPlatformEntryPoints(result, addedPackages, universalFreeform);

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

    AppProfile profile() {
        return mProfile;
    }

    boolean owns(final TaskRepository.TaskEntry task) {
        return task != null && mProfile.owns(task.userId);
    }

    AppItem findOrLoad(
            final List<AppItem> apps,
            final AppIdentity application,
            final boolean universalFreeform) {
        application.requireProfile(mProfile);
        final String packageName = application.packageName;
        final AppItem known = findApplication(apps, application);
        if (known != null) {
            return known;
        }
        final LauncherActivityInfo launcherInfo =
                findLauncherActivity(packageName);
        if (launcherInfo != null) {
            final CharSequence label = launcherInfo.getLabel();
            return new AppItem(
                    mProfile,
                    label == null ? packageName : label.toString(),
                    packageName,
                    universalFreeform,
                    fullscreenPreference(
                            launcherInfo.getActivityInfo(),
                            launcherInfo.getApplicationInfo()),
                    loadIcon(
                            launcherInfo.getActivityInfo(),
                            launcherInfo.getApplicationInfo()),
                    AppLaunchTarget.packageDefault(packageName));
        }
        try {
            final ApplicationInfo info =
                    mPackageManager.getApplicationInfo(packageName, 0);
            final ActivityInfo activityInfo =
                    resolveLauncherActivityInfo(packageName);
            final CharSequence label = info.loadLabel(mPackageManager);
            return new AppItem(
                    mProfile,
                    label == null ? packageName : label.toString(),
                    packageName,
                    universalFreeform,
                    fullscreenPreference(activityInfo, info),
                    loadIcon(activityInfo, info),
                    AppLaunchTarget.packageDefault(packageName));
        } catch (PackageManager.NameNotFoundException error) {
            Log.w(TAG, "Task package is not installed: " + packageName, error);
            return null;
        }
    }

    AppItem findOrLoad(
            final List<AppItem> apps,
            final AppIdentity application,
            final AppLaunchTarget target,
            final boolean universalFreeform) {
        application.requireProfile(mProfile);
        if (target == null || !application.packageName.equals(target.packageName)) {
            return null;
        }
        if (apps != null) {
            for (final AppItem app : apps) {
                if (application.equals(app.identity) && target.equals(app.launchTarget)) {
                    return app;
                }
            }
        }
        if (target.activityClassName.length() == 0) {
            return findOrLoad(apps, application, universalFreeform);
        }
        try {
            final ActivityInfo activityInfo =
                    mPackageManager.getActivityInfo(
                            new ComponentName(
                                    target.packageName,
                                    target.activityClassName),
                            0);
            final ApplicationInfo applicationInfo =
                    activityInfo.applicationInfo;
            final CharSequence activityLabel =
                    activityInfo.loadLabel(mPackageManager);
            final CharSequence applicationLabel = applicationInfo == null
                    ? null : applicationInfo.loadLabel(mPackageManager);
            final String label = activityLabel != null
                    && activityLabel.length() > 0
                    ? activityLabel.toString()
                    : applicationLabel != null
                            && applicationLabel.length() > 0
                            ? applicationLabel.toString()
                            : target.packageName;
            return new AppItem(
                    mProfile,
                    label,
                    target.packageName,
                    universalFreeform,
                    fullscreenPreference(activityInfo, applicationInfo),
                    loadIcon(activityInfo, applicationInfo),
                    target);
        } catch (PackageManager.NameNotFoundException error) {
            Log.w(TAG, "Launch target is not installed: "
                    + target.stableKey(), error);
            return null;
        }
    }

    private void addPlatformEntryPoints(
            final List<AppItem> result,
            final Set<AppIdentity> addedPackages,
            final boolean universalFreeform) {
        for (final AppLaunchTarget target
                : PlatformDrivers.current().additionalLaunchTargets()) {
            if (addedPackages.contains(mProfile.application(target.packageName))) {
                continue;
            }
            try {
                final ActivityInfo activityInfo = mPackageManager.getActivityInfo(
                        new ComponentName(
                                target.packageName,
                                target.activityClassName),
                        0);
                final ApplicationInfo applicationInfo =
                        activityInfo.applicationInfo;
                if (!activityInfo.exported
                        || !activityInfo.enabled
                        || applicationInfo == null
                        || !applicationInfo.enabled) {
                    continue;
                }
                final CharSequence activityLabel =
                        activityInfo.loadLabel(mPackageManager);
                final CharSequence applicationLabel =
                        applicationInfo.loadLabel(mPackageManager);
                final String label = activityLabel != null
                                && activityLabel.length() > 0
                        ? activityLabel.toString()
                        : applicationLabel != null
                                && applicationLabel.length() > 0
                                ? applicationLabel.toString()
                                : target.packageName;
                result.add(new AppItem(
                        mProfile,
                        label,
                        target.packageName,
                        universalFreeform,
                        fullscreenPreference(activityInfo, applicationInfo),
                        loadIcon(activityInfo, applicationInfo),
                        target));
                addedPackages.add(mProfile.application(target.packageName));
            } catch (PackageManager.NameNotFoundException error) {
                Log.d(TAG, "Optional platform entry point is unavailable: "
                        + target.packageName);
            }
        }
    }

    private void addMagicDeskEntryPoints(
            final List<AppItem> result,
            final Set<AppIdentity> addedPackages,
            final boolean universalFreeform) {
        for (final BuiltInDesktopAppCatalog.Entry entry
                : BuiltInDesktopAppCatalog.launcherEntries()) {
            final AppLaunchTarget target = entry.launchTarget;
            try {
                final ActivityInfo activityInfo =
                        mPackageManager.getActivityInfo(
                                new ComponentName(
                                        target.packageName,
                                        target.activityClassName),
                                0);
                if (!activityInfo.enabled
                        || activityInfo.applicationInfo == null
                        || !activityInfo.applicationInfo.enabled) {
                    continue;
                }
                final CharSequence label =
                        activityInfo.loadLabel(mPackageManager);
                result.add(new AppItem(
                        mProfile,
                        label == null || label.length() == 0
                                ? mContext.getString(
                                        entry.fallbackLabelResId)
                                : label.toString(),
                        target.packageName,
                        universalFreeform,
                        AppItem.FULLSCREEN_REASON_NONE,
                        loadIcon(
                                activityInfo,
                                activityInfo.applicationInfo),
                        target));
                addedPackages.add(mProfile.application(target.packageName));
            } catch (PackageManager.NameNotFoundException error) {
                Log.w(TAG, "Built-in desktop activity is unavailable: "
                        + target.activityClassName, error);
            }
        }
    }

    private LauncherActivityInfo findLauncherActivity(
            final String packageName) {
        if (mLauncherApps == null) {
            return null;
        }
        final List<LauncherActivityInfo> activities =
                mLauncherApps.getActivityList(
                        packageName,
                        Process.myUserHandle());
        return activities.isEmpty() ? null : activities.get(0);
    }

    static AppItem findApplication(final List<AppItem> apps, final AppIdentity application) {
        if (apps == null || application == null) {
            return null;
        }
        for (final AppItem app : apps) {
            if (application.equals(app.identity)) {
                return app;
            }
        }
        return null;
    }

    static AppItem find(
            final List<AppItem> apps,
            final AppReference reference) {
        if (apps == null || reference == null) {
            return null;
        }
        for (final AppItem app : apps) {
            if (reference.equals(app.reference)) {
                return app;
            }
        }
        return null;
    }

    private ActivityInfo resolveLauncherActivityInfo(final String packageName) {
        final LauncherActivityInfo launcherInfo =
                findLauncherActivity(packageName);
        return launcherInfo == null ? null : launcherInfo.getActivityInfo();
    }

    private Drawable loadIcon(
            final ActivityInfo activityInfo,
            final ApplicationInfo applicationInfo) {
        int resourceId = activityInfo == null
                ? 0 : activityInfo.getIconResource();
        if (resourceId == 0 && applicationInfo != null) {
            resourceId = applicationInfo.icon;
        }
        if (resourceId != 0 && applicationInfo != null) {
            try {
                final Resources resources =
                        mPackageManager.getResourcesForApplication(
                                applicationInfo);
                return mIconRenderer.render(resources.getDrawableForDensity(
                        resourceId,
                        DisplayMetrics.DENSITY_XHIGH,
                        null));
            } catch (PackageManager.NameNotFoundException
                    | RuntimeException ignored) {
                // Fall through to Android's default icon.
            }
        }
        return mIconRenderer.render(mPackageManager.getDefaultActivityIcon());
    }

    private static String fullscreenPreference(
            final ActivityInfo activityInfo,
            final ApplicationInfo applicationInfo) {
        if (activityInfo != null
                && (activityInfo.flags & ActivityInfo.FLAG_IMMERSIVE) != 0) {
            return AppItem.FULLSCREEN_REASON_IMMERSIVE;
        }
        if (activityInfo != null
                && (activityInfo.flags
                        & ActivityInfo.FLAG_PREFER_MINIMAL_POST_PROCESSING) != 0) {
            return AppItem.FULLSCREEN_REASON_GAME;
        }
        final Integer resizeMode = FrameworkActivityInfoCompat.resizeMode(
                activityInfo);
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

}
