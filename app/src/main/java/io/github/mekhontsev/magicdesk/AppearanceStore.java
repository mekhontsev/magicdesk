package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONException;

/** App-private global defaults and explicit stable workspace overrides; no Desktop dependency. */
public final class AppearanceStore {
    private record Resolved(WorkspaceAppearance state, Map<ShellResources, ThemeAssets.Prepared> assets) {
        ThemeAssets.Prepared assets(ShellAppearance appearance) {
            return assets.getOrDefault(appearance.resources(), ThemeAssets.Prepared.EMPTY);
        }
    }
    record ResolvedAppearance(ShellAppearance theme, ThemeAssets.Prepared assets) { }
    private static volatile Resolved sResolved = new Resolved(WorkspaceAppearance.defaults(), Map.of());
    private static Map<ShellResources, ThemeAssets.Prepared> sStaged = Map.of();
    private static WorkspaceAppearance sLoading;
    private static boolean sPruning;
    private static long sAssetGeneration;
    private static final CopyOnWriteArrayList<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static SharedPreferences sPreferences;
    private AppearanceStore() {}

    static synchronized void initialize(Context context) {
        if (sPreferences != null) return;
        SystemAppearancePalette.initialize(context, AppearanceStore::changed);
        sPreferences = context.getSharedPreferences("shell-appearance", Context.MODE_PRIVATE);
        WorkspaceAppearance loaded = WorkspaceAppearance.defaults();
        try { loaded = WorkspaceAppearance.restore(sPreferences.getString("document", "{}"), "{}"); }
        catch (Exception error) { android.util.Log.w("MagicDeskAppearance", "Invalid saved appearance", error); }
        try { loaded = WorkspaceAppearance.restore(loaded.savedGlobal(), sPreferences.getString("overrides", "{}")); }
        catch (Exception error) { android.util.Log.w("MagicDeskAppearance", "Invalid saved appearance overrides", error); }
        if (loaded.resources().stream().noneMatch(ShellResources::hasAssets)) {
            sResolved = new Resolved(loaded, Map.of());
            return;
        }
        final WorkspaceAppearance pending = loaded;
        final Resolved before = sResolved;
        final long generation = sAssetGeneration;
        final Context app = context.getApplicationContext();
        sLoading = pending;
        new Thread(() -> {
            try {
                var prepared = WorkspaceAppearanceAssets.prepare(app, pending.resources(), Map.of(), Map.of());
                synchronized (AppearanceStore.class) {
                    if (sPruning || sAssetGeneration != generation || sResolved != before || sLoading != pending) return;
                    sResolved = new Resolved(pending, prepared); sLoading = null;
                }
                changed();
            } catch (Exception error) {
                synchronized (AppearanceStore.class) { if (sLoading == pending) sLoading = null; }
                android.util.Log.w("MagicDeskAppearance", "Could not prepare saved appearance assets", error);
            }
        }, "MagicDeskAppearance").start();
    }

    public static ShellAppearance current() { return sResolved.state().current(); }
    public static ShellAppearance current(String scope) { return sResolved.state().current(scope); }
    public static ShellAppearance current(Context context) {
        String scope = AppearanceScopeBindings.find(context);
        WorkspaceAppearance state = sResolved.state();
        return scope == null ? state.current() : state.current(scope);
    }
    static ResolvedAppearance resolved(Context context) {
        String scope = AppearanceScopeBindings.find(context);
        Resolved resolved = sResolved;
        ShellAppearance theme = scope == null ? resolved.state().current() : resolved.state().current(scope);
        return new ResolvedAppearance(SystemAppearancePalette.resolve(theme), resolved.assets(theme));
    }
    /** Already-decoded, immutable resources for the same atomically published scoped appearance. */
    public static ThemeAssets.Prepared assets(Context context) { return resolved(context).assets(); }

    /** Bind the host's stable profile/workspace identity, never its transient Android display ID. */
    public static void bindScope(Context context, String scope) {
        if (AppearanceScopeBindings.bind(context, scope)) changed();
    }
    /** The live display hint is owned by this host binding and is never persisted. */
    public static void bindScope(Context context, int liveDisplayId, String scope) {
        if (liveDisplayId < 0) throw new IllegalArgumentException("Invalid live display ID");
        if (AppearanceScopeBindings.bind(context, liveDisplayId, scope)) changed();
    }
    public static void unbindScope(Context context) {
        if (AppearanceScopeBindings.unbind(context)) changed();
    }
    /** Stable identity for a bound workspace or an independent tool on its live display; null is global. */
    public static String scope(Context context) { return AppearanceScopeBindings.find(context); }
    public static void listen(Runnable listener) { LISTENERS.addIfAbsent(listener); }
    public static void unlisten(Runnable listener) { LISTENERS.remove(listener); }
    public static AppearanceTransaction.Snapshot snapshot() { return sResolved.state().snapshot(); }
    public static WorkspaceAppearance.Snapshot snapshot(String scope) { return sResolved.state().snapshot(scope); }

    /** Saved overrides, preview-only scopes, and live bindings; listing never persists a scope. */
    public static List<String> listScopes() {
        var scopes = new java.util.TreeSet<>(sResolved.state().listScopes());
        scopes.addAll(AppearanceScopeBindings.scopes());
        return List.copyOf(scopes);
    }
    public static String preview(ShellAppearance value) {
        return change(state -> state.preview(value), false).snapshot().previewId();
    }
    public static String preview(ShellAppearance value, long expectedRevision) {
        return change(state -> state.preview(value, expectedRevision), false).snapshot().previewId();
    }
    public static String preview(String scope, String patchJson) {
        return change(state -> state.preview(scope, patchJson), false).snapshot(scope).previewId();
    }
    public static String preview(String scope, String patchJson, long expectedRevision) {
        return change(state -> state.preview(scope, patchJson, expectedRevision), false).snapshot(scope).previewId();
    }
    public static void cancel(String id) { change(state -> state.cancel(id), false); }
    public static void cancel(String scope, String id) { change(state -> state.cancel(scope, id), false); }
    public static void confirm(String id) { change(state -> state.confirm(id), true); }
    public static void confirm(String scope, String id) { change(state -> state.confirm(scope, id), true); }
    public static void apply(ShellAppearance value) { change(state -> state.apply(value), true); }

    /** Replace this scope's patch; omitted fields inherit global defaults, not the previous patch. */
    public static void apply(String scope, String patchJson) { change(state -> state.apply(scope, patchJson), true); }
    public static void removeOverride(String scope) { change(state -> state.removeOverride(scope), true); }

    /** Explicit worker-only cleanup. Live, committed, preview and staged bundle references are retained. */
    public static java.util.Set<String> pruneUnusedBundles() throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Prune appearance bundles on a worker");
        }
        final java.util.Set<String> retained = new java.util.HashSet<>();
        synchronized (AppearanceStore.class) {
            if (sPreferences == null) initialize(MagicDeskApplication.applicationContext());
            if (sPruning || sLoading != null) throw new IllegalStateException("Appearance assets are already being prepared or cleaned up");
            for (ShellResources resources : sResolved.state().resources()) {
                if (!resources.bundle().isEmpty()) retained.add(resources.bundle());
            }
            for (ShellResources resources : sStaged.keySet()) {
                if (!resources.bundle().isEmpty()) retained.add(resources.bundle());
            }
            sPruning = true;
            // Invalidate preparations already in flight, including those completing after cleanup.
            sAssetGeneration++;
        }
        try { return ThemeAssets.get(MagicDeskApplication.applicationContext()).store().prune(retained); }
        finally { synchronized (AppearanceStore.class) { sPruning = false; } }
    }

    /** Worker preparation for a subsequent synchronous UI apply/preview; it publishes no theme. */
    public static void prepareAssets(ShellAppearance value) { prepareAssets(state -> state.apply(value)); }
    public static void prepareAssets(String scope, String patchJson) { prepareAssets(state -> state.apply(scope, patchJson)); }
    private static void prepareAssets(Mutation mutation) {
        final Resolved before;
        final WorkspaceAppearance base;
        final long generation;
        synchronized (AppearanceStore.class) {
            if (sPreferences == null) initialize(MagicDeskApplication.applicationContext());
            if (sPruning) throw new IllegalStateException("Appearance bundle cleanup is in progress; retry");
            before = sResolved; base = sLoading == null ? before.state() : sLoading;
            generation = sAssetGeneration;
        }
        try {
            WorkspaceAppearance next = mutation.apply(base);
            var resources = new LinkedHashSet<>(base.resources());
            resources.addAll(next.resources());
            var prepared = WorkspaceAppearanceAssets.prepare(MagicDeskApplication.applicationContext(), resources, before.assets(), Map.of());
            synchronized (AppearanceStore.class) {
                if (sPruning || sAssetGeneration != generation || sResolved != before) {
                    throw new IllegalStateException("Appearance or bundle storage changed during asset preparation; retry");
                }
                sStaged = prepared;
            }
        } catch (JSONException | IOException error) { throw new IllegalArgumentException(error.getMessage(), error); }
    }

    @FunctionalInterface private interface Mutation { WorkspaceAppearance apply(WorkspaceAppearance state) throws JSONException; }
    private static WorkspaceAppearance change(Mutation mutation, boolean persist) {
        final WorkspaceAppearance next;
        final Resolved before;
        final WorkspaceAppearance base;
        final Map<ShellResources, ThemeAssets.Prepared> staged;
        final long generation;
        synchronized (AppearanceStore.class) {
            if (sPreferences == null) initialize(MagicDeskApplication.applicationContext());
            if (sPruning) throw new IllegalStateException("Appearance bundle cleanup is in progress; retry");
            before = sResolved; base = sLoading == null ? before.state() : sLoading; staged = sStaged;
            generation = sAssetGeneration;
        }
        final Map<ShellResources, ThemeAssets.Prepared> prepared;
        try {
            next = mutation.apply(base);
            prepared = WorkspaceAppearanceAssets.prepare(MagicDeskApplication.applicationContext(), next.resources(), before.assets(), staged);
        } catch (JSONException | IOException error) { throw new IllegalArgumentException(error.getMessage(), error); }
        synchronized (AppearanceStore.class) {
            if (sPruning || sAssetGeneration != generation || sResolved != before) {
                throw new IllegalStateException("Appearance or bundle storage changed during preparation; retry");
            }
            if (persist) {
                sPreferences.edit().putString("document", next.savedGlobal())
                        .putString("overrides", next.savedOverrides()).apply();
            }
            sResolved = new Resolved(next, prepared); sStaged = Map.of(); sLoading = null;
        }
        changed();
        return next;
    }
    private static void changed() {
        if (Looper.myLooper() == Looper.getMainLooper()) publish();
        else new Handler(Looper.getMainLooper()).post(AppearanceStore::publish);
    }
    private static void publish() {
        UiAppearance.refresh();
        for (Runnable listener : LISTENERS) listener.run();
    }
}
