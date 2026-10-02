package io.github.mekhontsev.magicdesk;

import android.content.Context;
import org.json.JSONArray;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.ArrayList;

/** App-side adapter to the same shell-owned catalog used by the CLI. */
final class GuestEnvironmentCatalog {
    record Entry(String name, String id, String source, String store) {
        @Override public String toString() { return name; }
    }
    interface Loaded { void complete(List<Entry> entries, Throwable error); }

    static String command(String... arguments) {
        var words = new ArrayList<>(List.of(GuestLaunchPlan.TOOL));
        words.addAll(List.of(arguments));
        return String.join(" ", words.stream().map(ShellCommandLine::quote).toList());
    }

    static List<Entry> parse(String json) throws Exception {
        JSONArray items = new JSONArray(json);
        if (items.length() > 1024) throw new IOException("Too many guest environments");
        var result = new ArrayList<Entry>();
        for (int i = 0; i < items.length(); ++i) {
            var item = items.getJSONObject(i);
            String name = item.getString("name"), id = item.getString("id");
            GuestEnvironmentLibrary.validateName(name);
            if (!java.util.UUID.fromString(id).toString().equals(id)) throw new IOException("Invalid guest identity");
            result.add(new Entry(name, id, item.getString("source"), GuestEnvironment.absolute(item.getString("store"), "environment store")));
        }
        return List.copyOf(result);
    }

    static Closeable load(Context context, Loaded loaded) {
        return new CommandExecution(context, DesktopExecBackend.SHELL).start(command("list", "--json"), "", "Guest catalog", null,
                (code, output, error) -> {
                    final List<Entry> entries;
                    try {
                        if (error != null) throw new IOException("Cannot read guest catalog", error);
                        if (code != 0) throw new IOException(output);
                        entries = parse(output);
                    } catch (Exception failure) { loaded.complete(List.of(), failure); return; }
                    loaded.complete(entries, null);
                });
    }

    static String login(Entry entry) {
        return command("image", "login", entry.store());
    }
    private GuestEnvironmentCatalog() { }
}
