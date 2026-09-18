package io.github.mekhontsev.magicdesk;

import java.util.Objects;

/** Profile-scoped application entry, including a hosted application's stable launch recipe. */
final class AppReference {
    final AppIdentity application;
    final BuiltInDesktopAppCatalog.Entry builtIn;
    final String hostedRecipe;

    private AppReference(
            final AppIdentity application,
            final BuiltInDesktopAppCatalog.Entry builtIn) {
        this(application, builtIn, "");
    }

    private AppReference(AppIdentity application, BuiltInDesktopAppCatalog.Entry builtIn, String hostedRecipe) {
        this.application = Objects.requireNonNull(application);
        this.builtIn = builtIn;
        this.hostedRecipe = hostedRecipe;
    }

    static AppReference hosted(AppReference host, String recipeKey) {
        if (host == null || !BuiltInDesktopAppCatalog.hostsApplications(host.launchTarget())
                || recipeKey == null || !recipeKey.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid hosted application identity");
        }
        return new AppReference(host.application, host.builtIn, recipeKey);
    }

    AppReference windowStateKey() {
        return hostedRecipe.isEmpty() && builtIn != null
                && BuiltInDesktopAppCatalog.hostsApplications(builtIn.launchTarget) ? null : this;
    }

    static AppReference forTarget(final AppIdentity application, final AppLaunchTarget target) {
        if (application == null || target == null
                || !application.packageName.equals(target.packageName)) {
            throw new IllegalArgumentException("application entry mismatch");
        }
        final BuiltInDesktopAppCatalog.Entry builtIn = BuiltInDesktopAppCatalog.find(target);
        return BuildConfig.APPLICATION_ID.equals(application.packageName) && builtIn == null
                ? null : new AppReference(application, builtIn);
    }

    static AppReference forTask(final AppIdentity application, final TaskRepository.TaskEntry task) {
        if (application == null || task == null
                || !application.packageName.equals(task.packageName)) {
            return null;
        }
        final BuiltInDesktopAppCatalog.Entry builtIn = BuiltInDesktopAppCatalog.find(task);
        return BuildConfig.APPLICATION_ID.equals(application.packageName) && builtIn == null
                ? null : new AppReference(application, builtIn);
    }

    AppLaunchTarget launchTarget() {
        return builtIn == null ? AppLaunchTarget.packageDefault(application.packageName)
                : builtIn.launchTarget;
    }

    String persistentKey() {
        return application.persistentKey() + (builtIn == null ? ""
                : "|" + builtIn.launchTarget.activityClassName)
                + (hostedRecipe.isEmpty() ? "" : "|recipe|" + hostedRecipe);
    }

    static AppReference fromPersistentKey(final String key) {
        if (key == null) {
            throw new IllegalArgumentException("application reference is required");
        }
        final int recipe = key.indexOf("|recipe|");
        if (recipe >= 0) {
            final AppReference reference = hosted(fromPersistentKey(key.substring(0, recipe)), key.substring(recipe + 8));
            if (!reference.persistentKey().equals(key)) throw new IllegalArgumentException("invalid application reference");
            return reference;
        }
        final int component = key.indexOf('|', key.indexOf('|') + 1);
        final AppIdentity app = AppIdentity.fromPersistentKey(
                component < 0 ? key : key.substring(0, component));
        final AppReference reference;
        if (component < 0) {
            reference = forTarget(app, AppLaunchTarget.packageDefault(app.packageName));
        } else {
            final BuiltInDesktopAppCatalog.Entry entry =
                    BuiltInDesktopAppCatalog.findComponent(key.substring(component + 1));
            reference = entry == null ? null : forTarget(app, entry.launchTarget);
        }
        if (reference == null || !reference.persistentKey().equals(key)) {
            throw new IllegalArgumentException("invalid application reference");
        }
        return reference;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof AppReference
                && application.equals(((AppReference) other).application)
                && builtIn == ((AppReference) other).builtIn
                && hostedRecipe.equals(((AppReference) other).hostedRecipe);
    }

    @Override
    public int hashCode() {
        return Objects.hash(application, builtIn, hostedRecipe);
    }

    @Override
    public String toString() {
        return persistentKey();
    }
}
