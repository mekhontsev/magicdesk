package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.IBinder;
import android.os.Process;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Writes a regular Android preferred activity from the shell process. */
final class PreferredFileHandlerCommand {
    private PreferredFileHandlerCommand() {
    }

    static void set(
            final String mimeType,
            final String[] encodedCandidates,
            final String encodedSelected,
            final int match) {
        if (mimeType == null || encodedCandidates == null
                || encodedCandidates.length == 0) {
            throw new IllegalArgumentException("missing file handlers");
        }
        final ComponentName selected = decode(encodedSelected);
        final ComponentName[] candidates =
                new ComponentName[encodedCandidates.length];
        boolean selectedFound = false;
        for (int index = 0; index < encodedCandidates.length; index++) {
            candidates[index] = decode(encodedCandidates[index]);
            selectedFound |= selected.equals(candidates[index]);
        }
        if (!selectedFound) {
            throw new IllegalArgumentException(
                    "selected file handler is not a candidate");
        }

        final IntentFilter filter = new IntentFilter(Intent.ACTION_VIEW);
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        try {
            filter.addDataType(mimeType);
        } catch (IntentFilter.MalformedMimeTypeException error) {
            throw new IllegalArgumentException("invalid MIME type", error);
        }

        try {
            final Object packageManager = packageManager();
            final Method addPreferred = packageManager.getClass().getMethod(
                    "addPreferredActivity",
                    IntentFilter.class,
                    int.class,
                    ComponentName[].class,
                    ComponentName.class,
                    int.class,
                    boolean.class);
            addPreferred.invoke(
                    packageManager,
                    filter,
                    match,
                    candidates,
                    selected,
                    Process.myUid() / 100000,
                    true);
        } catch (InvocationTargetException error) {
            final Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(
                    "PackageManager rejected the preferred handler", cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                    "preferred-handler API is unavailable", error);
        }
    }

    static String getSelected(
            final String mimeType, final String dataUri) {
        if (mimeType == null || mimeType.isEmpty()) {
            return null;
        }
        final Intent intent = new Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_DEFAULT);
        if (dataUri == null || dataUri.isEmpty()) {
            intent.setType(mimeType);
        } else {
            intent.setDataAndType(Uri.parse(dataUri), mimeType);
        }
        try {
            final Object packageManager = packageManager();
            final Method getLastChosen = packageManager.getClass().getMethod(
                    "getLastChosenActivity",
                    Intent.class,
                    String.class,
                    int.class);
            final ResolveInfo resolved = (ResolveInfo) getLastChosen.invoke(
                    packageManager,
                    intent,
                    mimeType,
                    PackageManager.MATCH_DEFAULT_ONLY);
            if (resolved == null || resolved.activityInfo == null) {
                return null;
            }
            return new ComponentName(
                    resolved.activityInfo.packageName,
                    resolved.activityInfo.name).flattenToString();
        } catch (InvocationTargetException error) {
            final Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(
                    "PackageManager rejected handler lookup", cause);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(
                    "selected-handler API is unavailable", error);
        }
    }

    private static ComponentName decode(final String encoded) {
        final ComponentName component = encoded == null
                ? null : ComponentName.unflattenFromString(encoded);
        if (component == null) {
            throw new IllegalArgumentException("invalid file handler");
        }
        return component;
    }

    private static Object packageManager()
            throws ReflectiveOperationException {
        final IBinder binder = (IBinder) Class.forName(
                "android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "package");
        if (binder == null) {
            throw new IllegalStateException(
                    "PackageManager service is unavailable");
        }
        return Class.forName("android.content.pm.IPackageManager$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
    }
}
