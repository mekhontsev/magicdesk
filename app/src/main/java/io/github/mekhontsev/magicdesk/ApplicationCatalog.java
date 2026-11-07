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
            ApplicationCatalogSource.Snapshot<DesktopApplicationRepository.Entry> guest) {
        List<StartMenuEntry> applications(List<AppItem> apps,
                List<DesktopApplicationRepository.Entry> desktop) {
            final List<StartMenuEntry> entries = new ArrayList<>();
            for (AppItem app : apps) entries.add(StartMenuEntry.app(app));
            for (var entry : desktop) if (entry.shortcut.hasExecLaunch())
                entries.add(StartMenuEntry.desktopApplication(entry));
            for (var entry : termux.entries()) entries.add(StartMenuEntry.desktopApplication(entry));
            for (var entry : guest.entries()) entries.add(StartMenuEntry.desktopApplication(entry));
            return sortedUnique(entries);
        }
    }

    private final Application context;
    private final List<Runnable> listeners = new ArrayList<>();
    private final ApplicationCatalogSource<AppItem> android;
    private final ApplicationCatalogSource<DesktopApplicationRepository.Entry> termux;
    private final ApplicationCatalogSource<DesktopApplicationRepository.Entry> guest;
    private int guestOwner = -1;
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
        guest = new ApplicationCatalogSource<>(this::loadGuestSource, this::changed);
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

    Snapshot snapshot() { return new Snapshot(android.snapshot(), termux.snapshot(), guest.snapshot()); }

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
        if (inspectGuest()) guest.ensureLoaded();
    }

    void refreshApplications() {
        ApplicationIcons.refresh();
        android.invalidate(); termux.invalidate(); guest.invalidate();
        refresh();
    }

    private boolean inspectGuest() {
        int uid = ShellAccess.isReady() ? ShellAccess.currentSnapshot().uid : -1;
        if (uid != guestOwner) {
            guestOwner = uid;
            guest.reset("");
        }
        return true;
    }

    private void loadGuestSource(ApplicationCatalogSource.Completion<DesktopApplicationRepository.Entry> complete) {
        int owner = guestOwner;
        IO.execute(() -> {
            try {
                var entries = new java.util.ArrayList<DesktopApplicationRepository.Entry>();
                var errors = new java.util.ArrayList<String>();
                var backends = new java.util.ArrayList<DesktopExecBackend>();
                backends.add(DesktopExecBackend.APP);
                if (owner >= 0) backends.add(DesktopExecBackend.SHELL);
                for (var backend : backends) {
                    try {
                        var execution = new CommandExecution(context, backend);
                        var source = GuestApplicationRecords.parse(GuestEnvironmentOperations.read(execution, "", "applications"), backend);
                        entries.addAll(source.entries());
                        if (!source.error().isEmpty()) errors.add(backend.wireName + ": " + source.error());
                    } catch (Exception failure) { errors.add(backend.wireName + ": " + ShellAccess.usefulMessage(failure)); }
                }
                var result = new GuestApplicationRecords.Snapshot(List.copyOf(entries), String.join("; ", errors));
                MAIN.post(() -> {
                    inspectGuest();
                    if (owner != guestOwner) return;
                    if (!result.error().isEmpty()) CompatibilityDiagnostics.record("GUEST-APPS-001",
                            "Some guest applications could not be loaded", result.error());
                    complete.partial(result.entries(), result.error());
                });
            } catch (Exception error) {
                MAIN.post(() -> {
                    inspectGuest();
                    if (owner == guestOwner) {
                        CompatibilityDiagnostics.record("GUEST-APPS-001", "Could not load guest applications", "", error);
                        complete.complete(List.of(), ShellAccess.usefulMessage(error));
                    }
                });
            }
        });
    }

    static List<DesktopApplicationRepository.Entry> loadGuest(Context context) throws IOException {
        return loadGuest(context, true);
    }

    static List<DesktopApplicationRepository.Entry> loadGuest(Context context, boolean refresh) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("Guest catalog cannot block the UI");
        var ready = new CountDownLatch(1);
        var result = new AtomicReference<ApplicationCatalogSource.Snapshot<DesktopApplicationRepository.Entry>>();
        MAIN.post(() -> {
            try {
                var catalog = get(context);
                if (!catalog.inspectGuest()) { result.set(catalog.guest.snapshot()); ready.countDown(); }
                else if (!refresh && catalog.guest.snapshot().ready()) { result.set(catalog.guest.snapshot()); ready.countDown(); }
                else catalog.guest.refresh(value -> { result.set(value); ready.countDown(); });
            } catch (RuntimeException error) {
                result.set(new ApplicationCatalogSource.Snapshot<>(List.of(), false, false, ShellAccess.usefulMessage(error)));
                ready.countDown();
            }
        });
        try {
            // EVENT_WAIT: catalog completion; expiry fails this observation, not the shared load.
            EventDrivenWaits.noteFrameworkWait(EventDrivenWaits.Reason.APPLICATION_CATALOG);
            if (!ready.await(60, TimeUnit.SECONDS)) throw new IOException("Guest catalog query timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IOException("Guest catalog interrupted", error);
        }
        if (!result.get().ready() && !result.get().error().isEmpty()) throw new IOException(result.get().error());
        return result.get().entries();
    }

    void ensureAndroid() { android.ensureLoaded(); }

    void termuxApplicationsChanged() {
        ApplicationIcons.refresh();
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
        TermuxApplicationSource.load(context, endpoint, (entries, error, availableResult) -> {
            final boolean available = inspectTermux();
            if (!owner.equals(termuxOwner)) {
                if (available && !listeners.isEmpty()) termux.ensureLoaded();
                return;
            }
            complete.publish(entries, error, availableResult);
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
