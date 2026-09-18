package io.github.mekhontsev.magicdesk;

import android.app.Application;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.pm.LauncherApps;
import android.content.res.Configuration;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Process/profile-scoped discovery shared by Start windows and automation. No Desktop prerequisite. */
final class ApplicationCatalog {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r ->
            new Thread(r, "MagicDeskApplications"));
    private static volatile ApplicationCatalog instance;

    record Snapshot(ApplicationCatalogSource.Snapshot<AppItem> android,
            ApplicationCatalogSource.Snapshot<DesktopApplicationRepository.Entry> termux,
            java.util.Map<String, android.graphics.Bitmap> termuxIcons) {
        List<StartMenuEntry> applications(List<AppItem> apps,
                List<DesktopApplicationRepository.Entry> desktop) {
            final List<StartMenuEntry> entries = new ArrayList<>();
            for (AppItem app : apps) entries.add(StartMenuEntry.app(app));
            for (var entry : desktop) if (entry.shortcut.hasExecLaunch())
                entries.add(StartMenuEntry.desktopApplication(entry));
            for (var entry : termux.entries()) entries.add(StartMenuEntry.desktopApplication(entry));
            return sortedUnique(entries);
        }
    }

    private final Application context;
    private final List<Runnable> listeners = new ArrayList<>();
    private final ApplicationCatalogSource<AppItem> android;
    private final ApplicationCatalogSource<DesktopApplicationRepository.Entry> termux;
    private final ApplicationIconCache<android.graphics.Bitmap> icons;
    private TermuxIntegration.Endpoint endpoint;
    private String termuxOwner = "";
    private String lastTermuxError = "";
    private List<AppItem> floatingSource = List.of(), floatingApps = List.of();
    private Configuration configuration;

    static ApplicationCatalog get(Context context) {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Application catalog must be accessed on the UI thread");
        if (instance == null) instance = new ApplicationCatalog((Application) context.getApplicationContext());
        return instance;
    }

    private ApplicationCatalog(Application context) {
        this.context = context;
        configuration = new Configuration(context.getResources().getConfiguration());
        android = new ApplicationCatalogSource<>(this::loadAndroid, this::changed);
        termux = new ApplicationCatalogSource<>(this::loadTermuxSource, this::termuxChanged);
        icons = new ApplicationIconCache<>(TermuxIconCommand.BATCH_SIZE,
                (keys, complete) -> TermuxApplicationIcons.load(context, endpoint, IO, keys, complete),
                this::changed);
        final LauncherApps launcher = context.getSystemService(LauncherApps.class);
        if (launcher != null) launcher.registerCallback(new LauncherApps.Callback() {
            @Override public void onPackageAdded(String name, UserHandle user) { packagesChanged(); }
            @Override public void onPackageRemoved(String name, UserHandle user) { packagesChanged(); }
            @Override public void onPackageChanged(String name, UserHandle user) { packagesChanged(); }
            @Override public void onPackagesAvailable(String[] names, UserHandle user, boolean replacing) { packagesChanged(); }
            @Override public void onPackagesUnavailable(String[] names, UserHandle user, boolean replacing) { packagesChanged(); }
        }, MAIN);
        context.registerComponentCallbacks(new ComponentCallbacks() {
            @Override public void onConfigurationChanged(Configuration next) {
                final boolean changed = !configuration.getLocales().equals(next.getLocales())
                        || configuration.densityDpi != next.densityDpi || configuration.uiMode != next.uiMode;
                configuration = new Configuration(next);
                if (changed) {
                    android.invalidate();
                    if (!listeners.isEmpty()) android.ensureLoaded();
                }
            }
            @Override public void onLowMemory() { }
        });
    }

    Snapshot snapshot() { return new Snapshot(android.snapshot(), termux.snapshot(), icons.snapshot()); }

    static android.graphics.Bitmap cachedTermuxIcon(String name) {
        return instance == null ? null : instance.icons.snapshot().get(name);
    }

    List<AppItem> androidApps(boolean freeform) {
        final List<AppItem> apps = android.snapshot().entries();
        if (!freeform) return apps;
        if (floatingSource != apps) {
            floatingSource = apps;
            floatingApps = apps.stream().map(app -> new AppItem(app.profile, app.label, app.packageName,
                    true, app.fullscreenReason, app.icon, app.launchTarget)).toList();
        }
        return floatingApps;
    }

    void subscribe(Runnable listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    void unsubscribe(Runnable listener) { listeners.remove(listener); }

    void refresh() {
        ensureAndroid();
        if (inspectTermux()) termux.refresh(null);
    }

    void ensureAndroid() { android.ensureLoaded(); }

    void termuxApplicationsChanged() {
        termux.invalidate();
        refresh();
    }

    private void packagesChanged() {
        android.invalidate();
        final boolean available = inspectTermux();
        if (!listeners.isEmpty()) {
            android.ensureLoaded();
            if (available) termux.ensureLoaded();
        }
    }

    private boolean inspectTermux() {
        endpoint = TermuxIntegration.inspect(context);
        final String identity = endpoint.available()
                ? endpoint.service.flattenToString() + "|" + endpoint.uid + "|" + endpoint.homeDirectory
                : IntegrationPackage.TERMUX.selected() + "|unavailable";
        if (!identity.equals(termuxOwner)) {
            termuxOwner = identity;
            icons.reset();
            termux.reset(endpoint.error);
        }
        return endpoint.available();
    }

    private void loadAndroid(ApplicationCatalogSource.Completion<AppItem> complete) {
        IO.execute(() -> {
            try {
                final List<AppItem> result = new LauncherAppRepository(context).load(false);
                MAIN.post(() -> complete.complete(result, ""));
            } catch (RuntimeException error) {
                MAIN.post(() -> {
                    CompatibilityDiagnostics.record("APPS-CATALOG-001", "Could not load applications", "", error);
                    complete.complete(List.of(), ShellAccess.usefulMessage(error));
                });
            }
        });
    }

    private void loadTermuxSource(
            ApplicationCatalogSource.Completion<DesktopApplicationRepository.Entry> complete) {
        final String owner = termuxOwner;
        TermuxApplicationSource.load(context, endpoint, (entries, error) -> {
            final boolean available = inspectTermux();
            if (!owner.equals(termuxOwner)) {
                if (available && !listeners.isEmpty()) termux.ensureLoaded();
                return;
            }
            complete.complete(entries, error);
            if (error.isEmpty()) {
                final var names = new java.util.LinkedHashSet<String>();
                for (var entry : entries) if (TermuxIconCommand.valid(entry.shortcut.icon)) names.add(entry.shortcut.icon);
                icons.update(names);
            }
        });
    }

    private void changed() {
        for (Runnable listener : List.copyOf(listeners)) listener.run();
    }

    private void termuxChanged() {
        final var state = termux.snapshot();
        if (!state.loading()) {
            if (endpoint.available() && !state.error().isEmpty() && !state.error().equals(lastTermuxError)) {
                CompatibilityDiagnostics.record("TERMUX-APPS-001", "Could not load Termux applications", state.error());
            }
            lastTermuxError = state.error();
        }
        changed();
    }

    static List<StartMenuEntry> sortedUnique(List<StartMenuEntry> entries) {
        final var byKey = new LinkedHashMap<String, StartMenuEntry>();
        for (var entry : entries) byKey.putIfAbsent(entry.stableKey(), entry);
        final var result = new ArrayList<>(byKey.values());
        result.sort(Comparator.comparing((StartMenuEntry entry) -> entry.label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(StartMenuEntry::stableKey));
        return List.copyOf(result);
    }

    static List<DesktopApplicationRepository.Entry> loadTermux(Context context) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("Termux catalog query cannot block the UI");
        final var ready = new CountDownLatch(1);
        final var result = new AtomicReference<ApplicationCatalogSource.Snapshot<DesktopApplicationRepository.Entry>>();
        MAIN.post(() -> {
            try {
                final ApplicationCatalog catalog = get(context);
                if (!catalog.inspectTermux()) {
                    result.set(catalog.termux.snapshot());
                    ready.countDown();
                    return;
                }
                catalog.termux.refresh(value -> { result.set(value); ready.countDown(); });
            } catch (RuntimeException error) {
                result.set(new ApplicationCatalogSource.Snapshot<>(List.of(), false, false,
                        ShellAccess.usefulMessage(error)));
                ready.countDown();
            }
        });
        try {
            EventDrivenWaits.noteFrameworkWait(EventDrivenWaits.Reason.APPLICATION_CATALOG);
            if (!ready.await(16_000, TimeUnit.MILLISECONDS)) throw new IOException("Termux catalog query timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Termux catalog query was interrupted", error);
        }
        if (!result.get().error().isEmpty()) throw new IOException(result.get().error());
        return result.get().entries();
    }
}
