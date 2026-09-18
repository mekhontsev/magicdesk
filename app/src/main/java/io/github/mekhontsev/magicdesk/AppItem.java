package io.github.mekhontsev.magicdesk;

import android.graphics.drawable.Drawable;

final class AppItem {
    static final String FULLSCREEN_REASON_NONE = "none";
    static final String FULLSCREEN_REASON_IMMERSIVE = "immersive";
    static final String FULLSCREEN_REASON_UNRESIZEABLE = "unresizable";
    static final String FULLSCREEN_REASON_GAME = "game";

    final String label;
    final AppProfile profile;
    final AppIdentity identity;
    final AppReference reference;
    final String packageName;
    final boolean canFloat;
    final String fullscreenReason;
    final Drawable icon;
    final AppLaunchTarget launchTarget;

    AppItem(
            final AppProfile profile,
            final String label,
            final String packageName,
            final boolean canFloat,
            final String fullscreenReason,
            final Drawable icon,
            final AppLaunchTarget launchTarget) {
        this(profile, label, packageName, canFloat, fullscreenReason, icon, launchTarget,
                profile == null ? null : profile.reference(launchTarget));
    }

    private AppItem(AppProfile profile, String label, String packageName, boolean canFloat,
            String fullscreenReason, Drawable icon, AppLaunchTarget launchTarget, AppReference reference) {
        if (profile == null || launchTarget == null
                || !packageName.equals(launchTarget.packageName)) {
            throw new IllegalArgumentException("launch target package mismatch");
        }
        this.label = label;
        this.profile = profile;
        this.identity = profile.application(packageName);
        this.reference = reference;
        this.packageName = packageName;
        this.canFloat = canFloat;
        this.fullscreenReason = fullscreenReason;
        this.icon = icon;
        this.launchTarget = launchTarget;
    }

    AppItem withReference(AppReference value) {
        if (java.util.Objects.equals(reference, value)) return this;
        AppReference host = profile.reference(launchTarget);
        if (value != null && (!identity.equals(value.application)
                || host == null || value.builtIn != host.builtIn)) {
            throw new IllegalArgumentException("application entry mismatch");
        }
        return new AppItem(profile, label, packageName, canFloat, fullscreenReason, icon, launchTarget, value);
    }

    boolean matchesTask(final TaskRepository.TaskEntry task) {
        return task != null && profile.owns(task.userId)
                && launchTarget.matchesTask(task);
    }
}
