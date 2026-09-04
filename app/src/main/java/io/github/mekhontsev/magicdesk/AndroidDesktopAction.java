package io.github.mekhontsev.magicdesk;

import android.app.PendingIntent;

/** One semantic Android operation independent from the surface that invoked it. */
final class AndroidDesktopAction {
    enum Kind {
        REQUEST,
        PENDING_INTENT,
        SHORTCUT
    }

    final Kind kind;
    final String id;
    final String source;
    final AndroidIntegrationRequest request;
    final PendingIntent pendingIntent;
    final AndroidShortcutSpec shortcut;
    final DesktopLaunchPresentation presentation;

    private AndroidDesktopAction(
            final Kind kind,
            final String id,
            final String source,
            final AndroidIntegrationRequest request,
            final PendingIntent pendingIntent,
            final AndroidShortcutSpec shortcut,
            final DesktopLaunchPresentation presentation) {
        if (kind == null
                || (kind == Kind.REQUEST) != (request != null)
                || (kind == Kind.PENDING_INTENT) != (pendingIntent != null)
                || (kind == Kind.SHORTCUT) != (shortcut != null)) {
            throw new IllegalArgumentException("invalid Android action payload");
        }
        this.kind = kind;
        this.id = clean(id, "android-action");
        this.source = clean(source, "application");
        this.request = request;
        this.pendingIntent = pendingIntent;
        this.shortcut = shortcut;
        this.presentation = presentation == null
                ? DesktopLaunchPresentation.automatic() : presentation;
    }

    static AndroidDesktopAction request(
            final String id,
            final String source,
            final AndroidIntegrationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Android request is required");
        }
        return new AndroidDesktopAction(
                Kind.REQUEST,
                id,
                source,
                request,
                null,
                null,
                request.presentation);
    }

    static AndroidDesktopAction pendingIntent(
            final String id,
            final String source,
            final PendingIntent pendingIntent,
            final DesktopLaunchPresentation presentation) {
        if (pendingIntent == null) {
            throw new IllegalArgumentException("PendingIntent is required");
        }
        return new AndroidDesktopAction(
                Kind.PENDING_INTENT,
                id,
                source,
                null,
                pendingIntent,
                null,
                presentation);
    }

    static AndroidDesktopAction shortcut(
            final String id,
            final String source,
            final AndroidShortcutSpec shortcut,
            final DesktopLaunchPresentation presentation) {
        if (shortcut == null) {
            throw new IllegalArgumentException("Android shortcut is required");
        }
        return new AndroidDesktopAction(
                Kind.SHORTCUT,
                id,
                source,
                null,
                null,
                shortcut,
                presentation);
    }

    private static String clean(final String value, final String fallback) {
        return value == null || value.trim().isEmpty()
                ? fallback : value.trim();
    }
}
