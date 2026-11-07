package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Guest paths never become Android file paths; recipes capture immutable store identities. */
final class GuestApplicationRecords {
    record Snapshot(List<DesktopApplicationRepository.Entry> entries, String error) { }

    static Snapshot parse(String text) throws Exception { return parse(text, DesktopExecBackend.SHELL); }

    static Snapshot parse(String text, DesktopExecBackend backend) throws Exception {
        if (text.length() > GuestImageFiles.JSON_LIMIT) throw new IllegalArgumentException("Guest catalog too large");
        JSONArray records = new JSONArray(text);
        if (records.length() > 1024) throw new IllegalArgumentException("Too many guest applications");
        var entries = new ArrayList<DesktopApplicationRepository.Entry>();
        var errors = new ArrayList<String>();
        var ids = new HashSet<String>();
        for (int i = 0; i < records.length(); ++i) {
            String context = "Guest record " + (i + 1);
            try {
                var record = records.getJSONObject(i);
                var environment = GuestEnvironmentCatalog.parse(new JSONArray().put(record.getJSONObject("environment")).toString(), backend).get(0);
                context = environment.name();
                if (record.has("error")) {
                    errors.add(environment.name() + ": " + record.getString("error"));
                    continue;
                }
                String path = record.getString("path");
                String desktopId = ApplicationIconKey.desktopId(path);
                context += ": " + desktopId;
                String id = "guest:" + environment.id() + ":" + desktopId;
                if (!ids.add(id)) continue;
                var linux = DesktopEntryFile.parseGuestApplication(record.getString("text"));
                if (linux == null) continue;
                String command = DesktopExecTemplate.expandArguments(linux.exec, DesktopLaunchArguments.empty(), linux.name, linux.icon, path);
                var recipe = LinuxLaunchRecipe.build(linux.name + " (" + environment.name() + ")",
                        new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.MANAGED_GUEST, environment.store(), backend, ""),
                        "exec " + command, linux.workingDirectory, "", linux.terminal ? LinuxLaunchRecipe.Presentation.TERMINAL
                                : linux.graphics != null && linux.graphics.desktop() ? LinuxLaunchRecipe.Presentation.DESKTOP
                                : LinuxLaunchRecipe.Presentation.APPLICATION, linux.graphics == null ? GraphicalProtocol.X11 : linux.graphics.protocol());
                var shortcut = new DesktopApplicationShortcut(recipe.name, id, recipe.exec, null, "", recipe.launchMode,
                        false, recipe.execBackend, recipe.terminal).withLiteralExec(true).withGraphics(recipe.graphics == null ? null
                                : new GraphicalLaunchOptions(recipe.graphics.protocol(), recipe.graphics.desktop(), recipe.graphics.keyboardDirectory(),
                                        linux.graphics == null ? "" : linux.graphics.startupClass(), recipe.graphics.fileEnvironment(), recipe.graphics.connectionMode()));
                entries.add(new DesktopApplicationRepository.Entry(shortcut, "/magicdesk-guest/" + backend.wireName + "/" + environment.id() + "/applications/"
                        + desktopId, null, false, linux.exec));
            } catch (org.json.JSONException | java.io.IOException | IllegalArgumentException failure) {
                errors.add(context + ": " + failure.getMessage());
            }
        }
        return new Snapshot(List.copyOf(entries), String.join("; ", errors));
    }
    private GuestApplicationRecords() { }
}
