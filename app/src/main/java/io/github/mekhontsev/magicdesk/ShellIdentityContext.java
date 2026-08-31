package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.pm.PackageManager;

/** Creates framework clients whose declared package matches the shell UID. */
final class ShellIdentityContext {
    private static final String SHELL_PACKAGE = "com.android.shell";

    private ShellIdentityContext() {
    }

    static Context create(final Context context)
            throws PackageManager.NameNotFoundException {
        if (context == null) {
            throw new IllegalStateException("shell service context is unavailable");
        }
        return context.createPackageContext(
                SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY);
    }
}
