package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.os.IBinder;

import java.util.List;

/** User-scoped PackageManager preference for SECONDARY_HOME, not the HOME role. */
final class FrameworkSecondaryHomeApi {
    private final Object mService;
    private final Class<?> mApi;
    private final int mUserId;

    FrameworkSecondaryHomeApi(final int userId) throws ReflectiveOperationException {
        if (userId < 0) { throw new IllegalArgumentException("invalid HOME user"); }
        mUserId = userId;
        final IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "package");
        mApi = Class.forName("android.content.pm.IPackageManager");
        mService = Class.forName("android.content.pm.IPackageManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    String capture(final Context context) throws ReflectiveOperationException {
        final ComponentName selected = selected(intent());
        if (selected != null && !isOurs(selected)) {
            return selected.flattenToString();
        }
        return systemHome(context).flattenToString();
    }

    void claim() throws ReflectiveOperationException {
        replace(new ComponentName(BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + ".DesktopActivity"));
    }

    void restore(final Context context, final String previous)
            throws ReflectiveOperationException {
        final ComponentName saved = ComponentName.unflattenFromString(previous);
        if (saved == null || isOurs(saved)) {
            throw new IllegalArgumentException("invalid previous SECONDARY_HOME");
        }
        final boolean available = candidates(intent()).stream()
                .anyMatch(info -> saved.equals(component(info)));
        // Replace even after our component was disabled: Android may resolve a
        // fallback while still retaining a preference for that disabled component.
        replace(available ? saved : systemHome(context));
    }

    @SuppressLint("DiscouragedApi")
    private ComponentName systemHome(final Context context) throws ReflectiveOperationException {
        // This is Android's own secondary-launcher fallback, including resource overlays.
        final int id = context.getResources().getIdentifier(
                "config_secondaryHomePackage", "string", "android");
        final String packageName = id == 0 ? "" : context.getResources().getString(id);
        if (PackageNameValidator.isSafe(packageName)
                && !BuildConfig.APPLICATION_ID.equals(packageName)) {
            final ComponentName selected = selected(intent().setPackage(packageName));
            if (selected != null) { return selected; }
        }
        throw new IllegalStateException("Android system SECONDARY_HOME is unavailable");
    }

    private ComponentName selected(final Intent intent) throws ReflectiveOperationException {
        final ResolveInfo result = (ResolveInfo) mApi.getMethod("resolveIntent",
                Intent.class, String.class, long.class, int.class)
                .invoke(mService, intent, null, 0L, mUserId);
        final ComponentName component = component(result);
        // ResolverActivity is not a launcher. Accept only an actual enabled handler.
        return component != null && candidates(intent).stream()
                .anyMatch(info -> component.equals(component(info))) ? component : null;
    }

    @SuppressWarnings("unchecked")
    private List<ResolveInfo> candidates(final Intent intent) throws ReflectiveOperationException {
        final Object slice = mApi.getMethod("queryIntentActivities",
                Intent.class, String.class, long.class, int.class)
                .invoke(mService, intent, null, 0L, mUserId);
        return (List<ResolveInfo>) Class.forName("android.content.pm.ParceledListSlice")
                .getMethod("getList").invoke(slice);
    }

    private void replace(final ComponentName selected) throws ReflectiveOperationException {
        final List<ResolveInfo> handlers = candidates(intent());
        final ComponentName[] components = handlers.stream()
                .map(FrameworkSecondaryHomeApi::component).toArray(ComponentName[]::new);
        if (handlers.stream().noneMatch(info -> selected.equals(component(info)))) {
            throw new IllegalStateException("SECONDARY_HOME handler is unavailable: " + selected);
        }
        final int match = handlers.stream().mapToInt(info -> info.match).max().orElse(0)
                & IntentFilter.MATCH_CATEGORY_MASK;
        final IntentFilter filter = new IntentFilter(Intent.ACTION_MAIN);
        filter.addCategory(Intent.CATEGORY_SECONDARY_HOME);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        mApi.getMethod("replacePreferredActivity", IntentFilter.class, int.class,
                ComponentName[].class, ComponentName.class, int.class)
                .invoke(mService, filter, match, components, selected, mUserId);
        if (!selected.equals(selected(intent()))) {
            throw new IllegalStateException("SECONDARY_HOME preference did not converge: " + selected);
        }
    }

    private static Intent intent() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_SECONDARY_HOME);
    }

    private static ComponentName component(final ResolveInfo info) {
        return info == null || info.activityInfo == null ? null
                : new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
    }

    private static boolean isOurs(final ComponentName component) {
        return BuildConfig.APPLICATION_ID.equals(component.getPackageName());
    }
}
