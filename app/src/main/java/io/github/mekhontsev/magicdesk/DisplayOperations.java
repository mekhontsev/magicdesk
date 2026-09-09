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
            try {
                final DesktopDisplayInfo display = preview
                        ? DesktopDisplayCatalog.require(SimulatedDesktopDisplayController.create(spec), null)
                        : ShellAccess.createVirtualDisplay(spec);
                final DisplayProfileStore.Profile profile = DisplayProfileStore.load(
                        "display:simulated:" + display.uniqueId, spec.densityDpi);
                profile.dpi = spec.densityDpi;
                profile.dpiExplicit = true;
                DisplayProfileStore.save(profile);
                VirtualDisplayPreferences.save(MagicDeskApplication.applicationContext(), spec);
                callback.onComplete(display, null);
            } catch (IOException | RuntimeException error) {
                CompatibilityDiagnostics.record("DISPLAY-VIRTUAL-001",
                        "Could not create virtual display", error.getMessage(), error);
                callback.onComplete(null, error.getMessage());
            }
        });
    }

}
