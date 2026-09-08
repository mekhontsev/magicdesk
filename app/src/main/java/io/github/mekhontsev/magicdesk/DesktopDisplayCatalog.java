package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** One-shot live catalog shared by the panel, automation, and display actions. */
final class DesktopDisplayCatalog {
    private DesktopDisplayCatalog() { }

    static DesktopDisplayInfo[] read() throws IOException {
        final DesktopDisplayInfo[] displays = ShellAccess.listDesktopDisplays();
        for (int i = 0; i < displays.length; i++) {
            final DesktopDisplayInfo d = displays[i];
            if (SimulatedDesktopDisplayController.owns(d)) {
                displays[i] = new DesktopDisplayInfo(d.id, d.uniqueId, d.name, d.source,
                        d.width, d.height, d.densityDpi, d.canHostDesktop, true);
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

    static DesktopDisplayInfo requireOwned(final int id, final String uniqueId) throws IOException {
        final DesktopDisplayInfo display = require(id, uniqueId);
        if (!display.owned || id <= android.view.Display.DEFAULT_DISPLAY) {
            throw new IOException("display is not owned by MagicDesk: " + id);
        }
        return display;
    }

    static org.json.JSONObject json(final DesktopDisplayInfo display) throws org.json.JSONException {
        return new org.json.JSONObject().put("id", display.id).put("uniqueId", display.uniqueId)
                .put("name", display.name).put("source", display.source)
                .put("width", display.width).put("height", display.height)
                .put("densityDpi", display.densityDpi).put("canHostDesktop", display.canHostDesktop)
                .put("owned", display.owned).put("canRemove", display.owned)
                .put("scrcpyCommand", scrcpyCommand(display));
    }

    static String scrcpyCommand(final DesktopDisplayInfo display) {
        return "scrcpy --display-id=" + display.id
                + " --mouse-bind=++++ --shortcut-mod=rctrl";
    }
}
