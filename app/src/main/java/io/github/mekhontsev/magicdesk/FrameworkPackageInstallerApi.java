package io.github.mekhontsev.magicdesk;

import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;

import java.util.function.Consumer;

/** Shell installer flags and callbacks that must outlive the replaced APK process. */
final class FrameworkPackageInstallerApi {
    private FrameworkPackageInstallerApi() { }

    static PackageInstaller.SessionParams replacementParams(final String packageName) {
        final var params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(packageName);
        params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        try {
            // Android adds REPLACE_EXISTING for app installers, but shell must request it explicitly.
            final var flags = PackageInstaller.SessionParams.class.getField("installFlags");
            final int replace = PackageManager.class.getField("INSTALL_REPLACE_EXISTING").getInt(null);
            flags.setInt(params, flags.getInt(params) | replace);
            return params;
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Android package replacement unavailable", error);
        }
    }

    static IntentSender resultCallback(final Consumer<Intent> result) {
        final IIntentSender callback = new IIntentSender.Stub() {
            @Override public void send(int code, Intent intent, String resolvedType,
                    IBinder token, IIntentReceiver finished, String permission, Bundle options) {
                if (Binder.getCallingUid() != Process.SYSTEM_UID) {
                    throw new SecurityException("installer callback must come from Android");
                }
                if (intent != null) result.accept(intent);
            }
        };
        try {
            return IntentSender.class.getConstructor(IBinder.class).newInstance(callback.asBinder());
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Android installer callbacks unavailable", error);
        }
    }
}
