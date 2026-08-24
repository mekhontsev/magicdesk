package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.BoundedProcessRunner;
import io.github.mekhontsev.magicdesk.PlatformAudioCaptureDriver;
import io.github.mekhontsev.magicdesk.PlatformComponent;
import io.github.mekhontsev.magicdesk.PlatformDevice;

import android.os.Bundle;

import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Detects complete or component-level Nubia firmware integration. */
public final class NubiaFirmwareDetector {
    private static final String SERVICE_COMMAND = "/system/bin/service";
    private static final String PLATFORM_SERVICE = "redmagic.app.manager";
    private static final long TIMEOUT_MILLIS = 1_000L;
    private static final int MAX_OUTPUT_BYTES = 4 * 1024;

    public static final class Result {
        private final Set<PlatformComponent> mComponents;
        private final Map<PlatformComponent, String> mEvidence;
        private final String mSummary;

        private Result(
                final Set<PlatformComponent> components,
                final Map<PlatformComponent, String> evidence,
                final String summary) {
            mComponents = components.isEmpty()
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(EnumSet.copyOf(components));
            final EnumMap<PlatformComponent, String> evidenceCopy =
                    new EnumMap<>(PlatformComponent.class);
            evidenceCopy.putAll(evidence);
            mEvidence = Collections.unmodifiableMap(evidenceCopy);
            mSummary = summary == null ? "" : summary;
        }

        public boolean isAvailable() {
            return !mComponents.isEmpty();
        }

        public Set<PlatformComponent> components() {
            return mComponents;
        }

        public String summary() {
            return mSummary;
        }

        public String evidence(final PlatformComponent component) {
            final String value = mEvidence.get(component);
            return value == null ? "" : value;
        }
    }

    private NubiaFirmwareDetector() {
    }

    public static Result detect(final PlatformDevice device) {
        if (!isNubiaFamily(device)) {
            return unavailable("device family is not Nubia/REDMAGIC");
        }
        final boolean platformServicePresent = hasPlatformService();
        final boolean officialFingerprint = hasOfficialFingerprint(device);
        if (platformServicePresent || officialFingerprint) {
            final String evidence = platformServicePresent
                    ? "redmagic.app.manager service detected"
                    : "official Nubia/REDMAGIC firmware fingerprint";
            return complete(evidence);
        }
        return detectOptionalComponents();
    }

    public static Result complete(final String evidence) {
        final EnumSet<PlatformComponent> components =
                EnumSet.allOf(PlatformComponent.class);
        final EnumMap<PlatformComponent, String> componentEvidence =
                new EnumMap<>(PlatformComponent.class);
        for (final PlatformComponent component : components) {
            componentEvidence.put(component, evidence);
        }
        return new Result(components, componentEvidence, evidence);
    }

    public static Result unavailable(final String evidence) {
        return new Result(
                Collections.emptySet(),
                Collections.emptyMap(),
                evidence);
    }

    public static Result fromDetectedComponents(
            final Map<PlatformComponent, String> detected) {
        if (detected == null || detected.isEmpty()) {
            return unavailable(
                    "no optional Nubia firmware APIs were detected");
        }
        final EnumMap<PlatformComponent, String> evidence =
                new EnumMap<>(PlatformComponent.class);
        evidence.putAll(detected);
        evidence.put(
                PlatformComponent.DIAGNOSTICS,
                "Nubia hardware with optional vendor APIs");
        final String summary = "optional Nubia firmware components detected: "
                + joinComponents(evidence.keySet());
        return new Result(evidence.keySet(), evidence, summary);
    }

    static boolean hasCompleteFirmware(
            final PlatformDevice device,
            final boolean platformServicePresent) {
        return isNubiaFamily(device)
                && (platformServicePresent || hasOfficialFingerprint(device));
    }

    private static Result detectOptionalComponents() {
        final EnumMap<PlatformComponent, String> detected =
                new EnumMap<>(PlatformComponent.class);
        if (hasMethod(
                "android.hardware.display.IDisplayManager",
                "setCmdToDisplay",
                int.class, int.class, int.class, Bundle.class)) {
            detected.put(
                    PlatformComponent.PROJECTION,
                    "IDisplayManager#setCmdToDisplay detected");
        }
        if (hasPointerApi()) {
            detected.put(
                    PlatformComponent.POINTER,
                    "Nubia IInputManager pointer API detected");
        }
        if (hasMethod(
                "com.redmagic.os.RedMagicAppManager$Trigger",
                "openScreenOffTP",
                boolean.class)) {
            detected.put(
                    PlatformComponent.PHONE_UI,
                    "RedMagicAppManager phone-screen API detected");
        }
        if (hasInputRoutingApi()) {
            detected.put(
                    PlatformComponent.INPUT_ROUTING,
                    "IDisplayManager mirror-panel API detected");
        }
        if (hasMirrorTextApi()) {
            detected.put(
                    PlatformComponent.TEXT_INPUT,
                    "IDisplayManager mirror-text API detected");
        }
        if (InternalAudioSourceCapability.current().availability
                == PlatformAudioCaptureDriver.Availability.DECLARED) {
            detected.put(
                    PlatformComponent.AUDIO_CAPTURE,
                    "MediaRecorder source 80 declared by the framework");
        }
        if (NubiaHardwareNodes.anyPresent()) {
            detected.put(
                    PlatformComponent.SYSTEM_CONTROLS,
                    "REDMAGIC cooling nodes detected");
            detected.put(
                    PlatformComponent.RUNTIME,
                    "REDMAGIC cooling nodes detected");
        }
        return fromDetectedComponents(detected);
    }

    private static boolean hasPointerApi() {
        try {
            NubiaMouseController.prepareMousePositionControl();
            NubiaMouseController.preparePointerPositionControl();
            return true;
        } catch (ReflectiveOperationException | RuntimeException
                | LinkageError error) {
            return false;
        }
    }

    private static boolean hasInputRoutingApi() {
        try {
            new NubiaInputRoutingDriver().verifyApi();
            return true;
        } catch (ReflectiveOperationException | RuntimeException
                | LinkageError error) {
            return false;
        }
    }

    private static boolean hasMirrorTextApi() {
        try {
            NubiaMirrorTextInputDriver.INSTANCE.verifyApi();
            return true;
        } catch (ReflectiveOperationException | RuntimeException
                | LinkageError error) {
            return false;
        }
    }

    private static boolean hasMethod(
            final String className,
            final String methodName,
            final Class<?>... parameterTypes) {
        try {
            Class.forName(className).getMethod(methodName, parameterTypes);
            return true;
        } catch (ReflectiveOperationException | RuntimeException
                | LinkageError error) {
            return false;
        }
    }

    private static boolean hasOfficialFingerprint(
            final PlatformDevice device) {
        if (device == null) {
            return false;
        }
        final String fingerprint = device.fingerprint.toLowerCase(Locale.US);
        return fingerprint.startsWith("nubia/")
                || fingerprint.startsWith("redmagic/");
    }

    private static boolean isNubiaFamily(final PlatformDevice device) {
        return device != null
                && (device.familyNameContains("nubia")
                || device.familyNameContains("redmagic"));
    }

    private static boolean hasPlatformService() {
        final Process process;
        try {
            process = new ProcessBuilder(
                    SERVICE_COMMAND, "check", PLATFORM_SERVICE)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException error) {
            return false;
        }

        try {
            final BoundedProcessRunner.Result result =
                    BoundedProcessRunner.run(
                            process, TIMEOUT_MILLIS, MAX_OUTPUT_BYTES);
            return !result.truncated && reportsPresent(result.output);
        } catch (IOException error) {
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            process.destroy();
        }
    }

    static boolean reportsPresent(final String output) {
        if (output == null) {
            return false;
        }
        final String expected = "Service " + PLATFORM_SERVICE + ": found";
        for (final String line : output.split("\\R")) {
            if (expected.equals(line.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String joinComponents(
            final Set<PlatformComponent> components) {
        final StringBuilder result = new StringBuilder();
        for (final PlatformComponent component : components) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(component.wireName);
        }
        return result.toString();
    }
}
