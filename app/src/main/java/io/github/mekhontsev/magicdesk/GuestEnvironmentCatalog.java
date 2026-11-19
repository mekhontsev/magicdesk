package io.github.mekhontsev.magicdesk;

import android.content.Context;
import org.json.JSONArray;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

/** Catalogs belong to their captured executor; equal environment names are not interchangeable. */
final class GuestEnvironmentCatalog {
    record Entry(String name, String id, String source, String store, DesktopExecBackend backend) {
        Entry(String name, String id, String source, String store) { this(name, id, source, store, DesktopExecBackend.SHELL); }
        @Override public String toString() { return name; }
        String label(Context context) { return name + " (" + context.getString(backend.title()) + ")"; }
    }
    interface Loaded { void complete(List<Entry> entries, Throwable error); }

    static String command(String... arguments) {
        var words = new ArrayList<>(List.of(GuestLaunchPlan.TOOL));
        words.addAll(List.of(arguments));
        return String.join(" ", words.stream().map(ShellCommandLine::quote).toList());
    }

    static List<Entry> parse(String json) throws Exception { return parse(json, DesktopExecBackend.SHELL); }

    static List<Entry> parse(String json, DesktopExecBackend backend) throws Exception {
        JSONArray items = new JSONArray(json);
        if (items.length() > 1024) throw new IOException("Too many guest environments");
        var result = new ArrayList<Entry>();
        for (int i = 0; i < items.length(); ++i) {
            var item = items.getJSONObject(i);
            String name = item.getString("name"), id = item.getString("id");
            GuestEnvironmentLibrary.validateName(name);
            if (!java.util.UUID.fromString(id).toString().equals(id)) throw new IOException("Invalid guest identity");
            result.add(new Entry(name, id, item.getString("source"), GuestEnvironment.absolute(item.getString("store"), "environment store"), backend));
        }
        return List.copyOf(result);
    }

    static Closeable load(Context context, Loaded loaded) {
        var resources = new OperationResources();
        var backends = new ArrayList<>(List.of(DesktopExecBackend.APP));
        if (ShellAccess.isReady()) backends.add(DesktopExecBackend.SHELL);
        if (TermuxIntegration.isAvailable(context)) backends.add(DesktopExecBackend.TERMUX);
        loadNext(context, backends.iterator(), resources, new ArrayList<>(), null, loaded);
        return resources;
    }

    private static void loadNext(Context context, java.util.Iterator<DesktopExecBackend> pending,
            OperationResources resources, List<Entry> entries, Throwable firstError, Loaded loaded) {
        if (resources.isClosed()) return;
        if (!pending.hasNext()) { loaded.complete(List.copyOf(entries), firstError); return; }
        var slot = resources.reserve();
        Loaded next = (items, error) -> {
            entries.addAll(items);
            loadNext(context, pending, resources, entries, firstError == null ? error : firstError, loaded);
        };
        try { slot.attach(load(context, pending.next(), true, next)); }
        catch (RuntimeException error) { next.complete(List.of(), error); }
    }

    static Closeable load(Context context, DesktopExecBackend backend, Loaded loaded) {
        return load(context, backend, false, loaded);
    }

    private static Closeable load(Context context, DesktopExecBackend backend, boolean optional, Loaded loaded) {
        if (backend == DesktopExecBackend.TERMUX) {
            var endpoint = TermuxIntegration.inspect(context);
            var request = TermuxIntegration.runBackgroundShellCommandForResult(context, endpoint,
                    (optional ? "command -v shroot >/dev/null 2>&1 || { echo '[]'; exit 0; }; " : "")
                            + "exec shroot list", "Shroot catalog", endpoint.homeDirectory, 15_000,
                    (result, failure) -> {
                        if (failure != null || result == null || !result.success()) {
                            loaded.complete(List.of(), failure != null ? failure : new IOException(
                                    result == null ? "No Termux catalog result" : result.usefulMessage()));
                        } else deliver(result.stdout, backend, loaded);
                    });
            return () -> TermuxCommandResultReceiver.cancel(request);
        }
        return new CommandExecution(context, backend).start(command("list", "--json"), "", "Guest catalog", null,
                (code, output, error) -> {
                    if (error != null || code != 0) loaded.complete(List.of(), error != null ? error : new IOException(output));
                    else deliver(output, backend, loaded);
                });
    }

    private static void deliver(String output, DesktopExecBackend backend, Loaded loaded) {
        List<Entry> entries;
        try { entries = parse(output, backend); }
        catch (Exception failure) { loaded.complete(List.of(), failure); return; }
        loaded.complete(entries, null);
    }

    static String storeCommand(Entry entry, String operation, String... args) {
        var words = new ArrayList<>(entry.backend().shrootCli().storeCommand(operation, entry.store()));
        words.addAll(List.of(args));
        return String.join(" ", words.stream().map(ShellCommandLine::quote).toList());
    }

    static String login(Entry entry) {
        return storeCommand(entry, "login");
    }
    private GuestEnvironmentCatalog() { }
}
