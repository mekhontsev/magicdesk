package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class AutomationAppearance {
    static DesktopAutomationResult execute(String command, JSONObject arguments) throws JSONException {
        final String scope = workspaceKey(arguments);
        if (command.equals("appearance.themes")) {
            try {
                var context = MagicDeskApplication.applicationContext();
                JSONArray themes = new JSONArray();
                for (var entry : ShellThemes.ENTRIES) themes.put(new JSONObject().put("id", entry.id())
                        .put("name", context.getString(entry.title()))
                        .put("document", ShellAppearanceJson.encode(ShellThemes.load(entry.id(), context.getAssets()::open))));
                return DesktopAutomationResult.success("ok", new JSONObject().put("themes", themes));
            } catch (java.io.IOException error) {
                return DesktopAutomationResult.failure(DesktopAutomationErrorCode.ACTION_FAILED, error.getMessage(), false);
            }
        }
        if (command.equals("appearance.prune")) {
            try {
                var removed = AppearanceStore.pruneUnusedBundles();
                return DesktopAutomationResult.success("pruned", new JSONObject()
                        .put("removed", new JSONArray(removed.stream().sorted().toList())).put("count", removed.size()));
            } catch (java.io.IOException error) {
                return DesktopAutomationResult.failure(DesktopAutomationErrorCode.ACTION_FAILED, error.getMessage(), false);
            }
        }
        if (command.equals("appearance.import") || command.equals("appearance.export")) {
            try { return transfer(command, arguments, scope); }
            catch (java.io.IOException error) {
                return DesktopAutomationResult.failure(DesktopAutomationErrorCode.FILE_ACCESS_FAILED,
                        ShellAccess.usefulMessage(error), false);
            }
        }
        return executeScoped(command, arguments, scope);
    }

    static String format(JSONObject arguments) throws JSONException {
        String format = arguments.has("format") ? arguments.getString("format") : "zip";
        if (!format.equals("json") && !format.equals("zip")) throw new IllegalArgumentException("Expected json or zip format");
        return format;
    }

    private static DesktopAutomationResult transfer(String command, JSONObject arguments, String scope)
            throws JSONException, java.io.IOException {
        final String format = format(arguments);
        final ShellAppearance appearance;
        final String document;
        final long revision;
        if (scope == null) {
            var snapshot = AppearanceStore.snapshot();
            appearance = snapshot.current(); revision = snapshot.revision();
            document = ShellAppearanceJson.encode(appearance).toString(2);
        } else {
            var snapshot = AppearanceStore.snapshot(scope);
            appearance = snapshot.current(); revision = snapshot.revision(); document = snapshot.patch();
        }
        if (command.equals("appearance.export")) {
            String directory = ShellFilePathPolicy.absolute(arguments.getString("directory")).toString();
            String name = ShellFileNamePolicy.validate(arguments.optString("name", "magicdesk-theme." + format));
            try (var target = ShellAccess.beginShellFileCreation(directory, name)) {
                try (var output = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(target.open())) {
                    if (format.equals("zip")) AppearanceBundles.write(appearance, output);
                    else output.write(document.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                target.commit();
                return DesktopAutomationResult.success("exported", new JSONObject().put("path", target.file.absolutePath)
                        .put("format", format).put("workspaceKey", scope == null ? JSONObject.NULL : scope));
            }
        }
        String path = ShellFilePathPolicy.absolute(arguments.getString("path")).toString();
        final var file = ShellAccess.getShellFileInfo(path);
        final long limit = format.equals("zip") ? ThemeBundleLimits.DEFAULT.archiveBytes() : ShellAppearanceJson.MAX_BYTES;
        if (file == null || file.symbolicLink || !file.isRegularFile() || file.size > limit) {
            throw new java.io.IOException("Appearance import requires an ordinary file within its byte budget");
        }
        final String imported;
        try (var input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(ShellAccess.openVerifiedShellFile(file, "r"))) {
            if (format.equals("zip")) imported = ShellAppearanceJson.encode(AppearanceBundles.read(input)).toString();
            else {
                byte[] bytes = input.readNBytes(ShellAppearanceJson.MAX_BYTES + 1);
                if (bytes.length > ShellAppearanceJson.MAX_BYTES) throw new java.io.IOException("Appearance document exceeds 32 KiB");
                imported = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        if (scope == null) AppearanceStore.prepareAssets(ShellAppearanceJson.parse(imported));
        else AppearanceStore.prepareAssets(scope, imported);
        if (scope == null) AppearanceStore.preview(ShellAppearanceJson.parse(imported), revision);
        else AppearanceStore.preview(scope, imported, revision);
        return DesktopAutomationResult.success("preview", state(scope));
    }

    static String workspaceKey(JSONObject arguments) throws JSONException {
        return arguments.has("workspaceKey") ? WorkspaceAppearance.requireScope(arguments.getString("workspaceKey")) : null;
    }

    private static DesktopAutomationResult executeScoped(String command, JSONObject arguments, String scope) throws JSONException {
        switch (command) {
            case "appearance.get" -> { }
            case "appearance.schema" -> {
                return DesktopAutomationResult.success("ok", new JSONObject().put("schema", ShellAppearanceSchema.document()));
            }
            case "appearance.validate" -> {
                var value = resolve(arguments.getJSONObject("document").toString(), scope);
                return DesktopAutomationResult.success("valid", new JSONObject().put("document", ShellAppearanceJson.encode(value)));
            }
            case "appearance.preview" -> {
                String document = arguments.getJSONObject("document").toString();
                if (scope == null) AppearanceStore.preview(ShellAppearanceJson.parse(document));
                else AppearanceStore.preview(scope, document);
            }
            case "appearance.confirm" -> {
                if (scope == null) AppearanceStore.confirm(arguments.getString("previewId"));
                else AppearanceStore.confirm(scope, arguments.getString("previewId"));
            }
            case "appearance.cancel" -> {
                if (scope == null) AppearanceStore.cancel(arguments.getString("previewId"));
                else AppearanceStore.cancel(scope, arguments.getString("previewId"));
            }
            case "appearance.apply" -> {
                String document = arguments.getJSONObject("document").toString();
                if (scope == null) AppearanceStore.apply(ShellAppearanceJson.parse(document));
                else AppearanceStore.apply(scope, document);
            }
            case "appearance.preset" -> {
                var preset = ShellAppearance.preset(arguments.getString("name"));
                if (scope == null) AppearanceStore.apply(AppearanceStore.current().withStyle(preset));
                else {
                    var patch = new JSONObject(AppearanceStore.snapshot(scope).patch());
                    var style = ShellAppearanceJson.encode(preset);
                    for (String key : new String[] {"palette", "colors", "typography", "shape", "feedback", "controls"}) patch.put(key, style.get(key));
                    AppearanceStore.apply(scope, patch.toString());
                }
            }
            case "appearance.reset" -> {
                if (scope == null) AppearanceStore.apply(ShellAppearance.defaults());
                else AppearanceStore.removeOverride(scope);
            }
            default -> throw new IllegalArgumentException("Unknown appearance operation");
        }
        return DesktopAutomationResult.success("ok", state(scope));
    }

    static ShellAppearance resolve(String document, String scope) throws JSONException {
        return scope == null ? ShellAppearanceJson.parse(document)
                : WorkspaceAppearancePatch.parse(document).resolve(AppearanceStore.current());
    }

    private static JSONObject state(String scope) throws JSONException {
        JSONArray signals = new JSONArray();
        long now = System.nanoTime();
        for (var signal : AppearanceSignalSources.snapshot()) {
            boolean available = signal.sample().availableAt(now);
            signals.put(new JSONObject().put("source", signal.source().id).put("subscribers", signal.subscribers())
                    .put("available", available).put("value", available ? signal.sample().value() : JSONObject.NULL));
        }
        JSONObject result = new JSONObject().put("workspaceKey", scope == null ? JSONObject.NULL : scope)
                .put("signalSources", signals)
                .put("workspaceKeys", new JSONArray(AppearanceStore.listScopes()))
                .put("presets", new JSONArray().put("dark").put("light").put("contrast"));
        if (scope == null) {
            var state = AppearanceStore.snapshot();
            return result.put("document", ShellAppearanceJson.encode(state.current()))
                    .put("committed", ShellAppearanceJson.encode(state.committed()))
                    .put("previewId", state.previewId() == null ? JSONObject.NULL : state.previewId())
                    .put("revision", state.revision()).put("patch", JSONObject.NULL).put("committedPatch", JSONObject.NULL);
        }
        var state = AppearanceStore.snapshot(scope);
        return result.put("document", ShellAppearanceJson.encode(state.current()))
                .put("committed", ShellAppearanceJson.encode(state.committed()))
                .put("patch", new JSONObject(state.patch())).put("committedPatch", new JSONObject(state.committedPatch()))
                .put("previewId", state.previewId() == null ? JSONObject.NULL : state.previewId()).put("revision", state.revision());
    }
}
