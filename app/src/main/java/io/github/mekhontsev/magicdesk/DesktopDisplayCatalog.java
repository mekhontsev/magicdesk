package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** One-shot live catalog shared by the panel, automation, and display actions. */
final class DesktopDisplayCatalog {
    private DesktopDisplayCatalog() { }

    static DesktopDisplayInfo[] read() throws IOException {
        if (!ShellAccess.isReady()) return ApplicationDisplayCatalog.read();
        final DesktopDisplayInfo[] displays = ShellAccess.listDesktopDisplays();
        for (int i = 0; i < displays.length; i++) {
            final DesktopDisplayInfo d = displays[i];
            if (SimulatedDesktopDisplayController.owns(d)) {
                displays[i] = new DesktopDisplayInfo(d.id, d.uniqueId, d.systemName, d.name, d.source,
                        d.width, d.height, d.densityDpi, d.canHostDesktop, d.requiresPortableDesktop, true, d.secure);
            }
        }
        return displays;
    }

    static DesktopDisplayInfo require(final int id, final String uniqueId) throws IOException {
        for (final DesktopDisplayInfo display : read()) {
            if (display.id == id && (uniqueId == null || display.uniqueId.equals(uniqueId))) {
                return display;
            }
        }
        throw new IOException("display is no longer available: " + id);
    }

    static DesktopDisplayInfo findForRemoval(final int id, final String uniqueId) throws IOException {
        DisplayRemovalRequests.validate(id, uniqueId);
        for (final DesktopDisplayInfo display : read()) {
            if (display.id != id) continue;
            if (!uniqueId.equals(display.uniqueId)) {
                throw new IOException("display identity changed: " + id);
            }
            if (!display.canRemove()) {
                throw new IOException("display is not owned by MagicDesk: " + id);
            }
            return display;
        }
        return null;
    }

    static org.json.JSONObject json(final DesktopDisplayInfo display) throws org.json.JSONException {
        final boolean connectionIdentity = display.uniqueId.startsWith("app-display:");
        final String profileKey = connectionIdentity ? null : DisplayProfiles.key(display);
        final DisplayProfileStore.Profile profile = profileKey == null || !ShellAccess.isReady() ? null
                : DisplayProfileStore.load(profileKey, display.densityDpi);
        return new org.json.JSONObject().put("id", display.id).put("uniqueId", display.uniqueId)
                .put("identityScope", connectionIdentity ? "connection" : "system")
                .put("profileKey", profileKey == null ? org.json.JSONObject.NULL : profileKey)
                .put("originProfileKey", profile == null ? org.json.JSONObject.NULL : DisplayProfiles.origin(profile))
                .put("profile", profile == null ? org.json.JSONObject.NULL : new org.json.JSONObject().put("densityDpi", profile.dpiExplicit ? profile.dpi : org.json.JSONObject.NULL)
                        .put("width", profile.width > 0 ? profile.width : org.json.JSONObject.NULL)
                        .put("height", profile.height > 0 ? profile.height : org.json.JSONObject.NULL))
                .put("name", display.name).put("systemName", display.systemName).put("source", display.source)
                .put("width", display.width).put("height", display.height)
                .put("densityDpi", display.densityDpi).put("canHostDesktop", display.canHostDesktop)
                .put("requiresPortableDesktop", display.requiresPortableDesktop)
                .put("owned", display.owned).put("canRemove", display.canRemove())
                .put("secure", display.secure).put("protectedContent", display.protectedContent())
                .put("defaultDisplay", display.isDefaultDisplay())
                .put("builtIn", "unknown".equals(display.source) ? org.json.JSONObject.NULL : display.isBuiltIn())
                .put("scrcpyCommand", scrcpyCommand(display));
    }

    static String scrcpyCommand(final DesktopDisplayInfo display) {
        return "scrcpy --display-id=" + display.id
                + " --mouse-bind=++++ --shortcut-mod=rctrl";
    }
}
