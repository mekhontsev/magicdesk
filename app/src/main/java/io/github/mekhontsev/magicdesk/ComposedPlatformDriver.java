package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/** Standard Android behavior with optional component-level firmware overrides. */
final class ComposedPlatformDriver implements PlatformDriver {
    private final PlatformDriver mBaseline;
    private final PlatformExtension mExtension;
    private final PlatformFeatures mFeatures;
    private final PlatformSelection mSelection;
    private final Set<PlatformComponent> mExtensionComponents;

    private ComposedPlatformDriver(
            final PlatformDriver baseline,
            final PlatformExtension extension,
            final PlatformMatch match) {
        mBaseline = baseline;
        mExtension = extension;
        final Set<PlatformComponent> declaredComponents =
                extension.components();
        if (declaredComponents == null) {
            throw new IllegalArgumentException(
                    "extension components are required");
        }
        mExtensionComponents = declaredComponents.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(
                        EnumSet.copyOf(declaredComponents));
        mFeatures = extension.extendFeatures(baseline.features());
        if (mFeatures == null) {
            throw new IllegalArgumentException(
                    "extension features are required");
        }
        final PlatformSelection.Builder selection =
                PlatformSelection.baseline(baseline.id())
                        .extension(extension.id(), match.evidence);
        for (final PlatformComponent component : mExtensionComponents) {
            final String componentEvidence =
                    extension.componentEvidence(component);
            selection.provider(
                    component,
                    extension.id(),
                    componentEvidence == null || componentEvidence.isEmpty()
                            ? match.evidence : componentEvidence);
        }
        mSelection = selection.build();
    }

    static PlatformDriver compose(
            final PlatformDriver baseline,
            final PlatformExtension extension,
            final PlatformMatch match) {
        if (baseline == null) {
            throw new IllegalArgumentException("baseline driver is required");
        }
        if (extension == null || match == null || !match.matched) {
            return baseline;
        }
        return new ComposedPlatformDriver(baseline, extension, match);
    }

    @Override
    public String id() {
        return mExtension.id();
    }

    @Override
    public String name() {
        return mExtension.name();
    }

    @Override
    public boolean supports(final PlatformDevice device) {
        return mBaseline.supports(device);
    }

    @Override
    public PlatformFeatures features() {
        return mFeatures;
    }

    @Override
    public PlatformSelection selection() {
        return mSelection;
    }

    @Override
    public PlatformWindowingDriver windowing() {
        return component(
                PlatformComponent.WINDOWING,
                mExtension.windowing(), mBaseline.windowing());
    }

    @Override
    public PlatformPointerDriver pointer() {
        return component(
                PlatformComponent.POINTER,
                mExtension.pointer(), mBaseline.pointer());
    }

    @Override
    public PlatformProjectionDriver projection() {
        return component(
                PlatformComponent.PROJECTION,
                mExtension.projection(), mBaseline.projection());
    }

    @Override
    public PlatformPhoneUiDriver phoneUi() {
        return component(
                PlatformComponent.PHONE_UI,
                mExtension.phoneUi(), mBaseline.phoneUi());
    }

    @Override
    public PlatformWallpaperDriver wallpaper() {
        return component(
                PlatformComponent.WALLPAPER,
                mExtension.wallpaper(), mBaseline.wallpaper());
    }

    @Override
    public PlatformDiagnostics diagnostics() {
        return component(
                PlatformComponent.DIAGNOSTICS,
                mExtension.diagnostics(), mBaseline.diagnostics());
    }

    @Override
    public PlatformAudioCaptureDriver audioCapture() {
        return component(
                PlatformComponent.AUDIO_CAPTURE,
                mExtension.audioCapture(), mBaseline.audioCapture());
    }

    @Override
    public PlatformTextInputDriver textInput() {
        return component(
                PlatformComponent.TEXT_INPUT,
                mExtension.textInput(), mBaseline.textInput());
    }

    @Override
    public PlatformSystemControls createSystemControls(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        if (!provides(PlatformComponent.SYSTEM_CONTROLS)) {
            return mBaseline.createSystemControls(activity, ui);
        }
        return requireComponent(
                PlatformComponent.SYSTEM_CONTROLS,
                mExtension.createSystemControls(activity, ui));
    }

    @Override
    public List<AppLaunchTarget> additionalLaunchTargets() {
        return component(
                PlatformComponent.LAUNCH_TARGETS,
                mExtension.additionalLaunchTargets(),
                mBaseline.additionalLaunchTargets());
    }

    @Override
    public void startRuntime(final Context context) {
        mBaseline.startRuntime(context);
        if (provides(PlatformComponent.RUNTIME)) {
            mExtension.startRuntime(context);
        }
    }

    @Override
    public void stopRuntime() {
        if (provides(PlatformComponent.RUNTIME)) {
            mExtension.stopRuntime();
        }
        mBaseline.stopRuntime();
    }

    @Override
    public void restoreRuntimeState(final Consumer<Boolean> callback) {
        mBaseline.restoreRuntimeState(baselineRestored -> {
            if (!Boolean.TRUE.equals(baselineRestored)
                    || !provides(PlatformComponent.RUNTIME)) {
                if (callback != null) {
                    callback.accept(baselineRestored);
                }
                return;
            }
            mExtension.restoreRuntimeState(callback);
        });
    }

    private boolean provides(final PlatformComponent component) {
        return mExtensionComponents.contains(component);
    }

    private <T> T component(
            final PlatformComponent component,
            final T extension,
            final T baseline) {
        return provides(component)
                ? requireComponent(component, extension) : baseline;
    }

    private static <T> T requireComponent(
            final PlatformComponent component,
            final T value) {
        if (value == null) {
            throw new IllegalStateException(
                    "extension declared " + component.wireName
                            + " without an implementation");
        }
        return value;
    }
}
