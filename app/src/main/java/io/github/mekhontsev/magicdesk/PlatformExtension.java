package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Optional firmware integration layered over the Standard Android driver. */
public interface PlatformExtension {
    String id();

    String name();

    PlatformMatch match(PlatformDevice device);

    Set<PlatformComponent> components();

    default String componentEvidence(final PlatformComponent component) {
        return "";
    }

    PlatformFeatures extendFeatures(PlatformFeatures baseline);

    default PlatformWindowingDriver windowing() {
        return null;
    }

    default PlatformPointerDriver pointer() {
        return null;
    }

    default PlatformProjectionDriver projection() {
        return null;
    }

    default PlatformPhoneUiDriver phoneUi() {
        return null;
    }

    default PlatformWallpaperDriver wallpaper() {
        return null;
    }

    default PlatformDiagnostics diagnostics() {
        return null;
    }

    default PlatformAudioCaptureDriver audioCapture() {
        return null;
    }

    default PlatformTextInputDriver textInput() {
        return null;
    }

    default PlatformSystemControls createSystemControls(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        return null;
    }

    default List<AppLaunchTarget> additionalLaunchTargets() {
        return null;
    }

    default void startRuntime(final Context context) {
    }

    default void stopRuntime() {
    }

    default void restoreRuntimeState(final Consumer<Boolean> callback) {
        if (callback != null) {
            callback.accept(Boolean.TRUE);
        }
    }
}
