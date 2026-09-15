package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** Display resources can be prepared without starting any desktop runtime. */
final class DisplayOperations {
    private DisplayOperations() { }

    interface DisplayCallback {
        void onComplete(DesktopDisplayInfo display, String error);
    }

    interface DisplayCatalogCallback {
        void onComplete(DesktopDisplayInfo[] displays, String error);
    }

    static void readDisplays(final DisplayCatalogCallback callback) {
        TaskCommandQueue.execute(() -> {
            try {
                callback.onComplete(DesktopDisplayCatalog.read(), null);
            } catch (IOException | RuntimeException error) {
                callback.onComplete(new DesktopDisplayInfo[0], error.getMessage());
            }
        });
    }

    static void createDisplay(final VirtualDisplaySpec spec, final boolean preview,
            final DisplayCallback callback) {
        TaskCommandQueue.execute(() -> {
            DesktopDisplayInfo display = null;
            String failure = null;
            try {
                if (preview) spec.requireOverlayCompatible();
                display = preview
                        ? DesktopDisplayCatalog.require(SimulatedDesktopDisplayController.create(spec), null)
                        : ShellAccess.createVirtualDisplay(spec);
                if (!DisplayProfileStore.save(DisplayProfiles.createdProfile(display, spec))) {
                    throw new IOException("Display created, but its profile could not be saved; display retained: "
                            + display.id + " [" + display.uniqueId + "]");
                }
                VirtualDisplayPreferences.save(MagicDeskApplication.applicationContext(), spec);
            } catch (IOException | RuntimeException error) {
                CompatibilityDiagnostics.record("DISPLAY-VIRTUAL-001",
                        "Could not create virtual display", error.getMessage(), error);
                failure = error.getMessage();
            }
            callback.onComplete(display, failure);
        });
    }

}
