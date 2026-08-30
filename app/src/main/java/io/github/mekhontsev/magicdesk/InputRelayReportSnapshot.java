package io.github.mekhontsev.magicdesk;

import android.graphics.Point;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One-shot input observation captured at the start of report generation. */
final class InputRelayReportSnapshot {
    private static final String MAGICDESK_MOUSE_PORT = "magicdesk-mouse";
    private static final String MAGICDESK_KEYBOARD_PREFIX =
            "magicdesk-keyboard-";

    final InputBridgeDiagnostics.Snapshot lifecycle;
    final InputRelayRuntimeDiagnostics.Snapshot runtime;
    final boolean touchpadRequested;
    final boolean touchpadVisible;
    final int physicalMice;
    final int virtualMice;
    final int physicalKeyboards;
    final int virtualKeyboards;
    final Set<String> ownedPorts;
    final Map<String, String> activeAssociations;
    final Set<String> missingAssociations;
    final Set<String> unexpectedAssociations;
    final String inputStateError;

    private InputRelayReportSnapshot(
            final InputBridgeDiagnostics.Snapshot lifecycle,
            final InputRelayRuntimeDiagnostics.Snapshot runtime,
            final boolean touchpadRequested,
            final boolean touchpadVisible,
            final int physicalMice,
            final int virtualMice,
            final int physicalKeyboards,
            final int virtualKeyboards,
            final Set<String> ownedPorts,
            final Map<String, String> activeAssociations,
            final Set<String> missingAssociations,
            final Set<String> unexpectedAssociations,
            final String inputStateError) {
        this.lifecycle = lifecycle;
        this.runtime = runtime;
        this.touchpadRequested = touchpadRequested;
        this.touchpadVisible = touchpadVisible;
        this.physicalMice = physicalMice;
        this.virtualMice = virtualMice;
        this.physicalKeyboards = physicalKeyboards;
        this.virtualKeyboards = virtualKeyboards;
        this.ownedPorts = ownedPorts;
        this.activeAssociations = activeAssociations;
        this.missingAssociations = missingAssociations;
        this.unexpectedAssociations = unexpectedAssociations;
        this.inputStateError = inputStateError;
    }

    static InputRelayReportSnapshot capture() {
        final InputBridgeDiagnostics.Snapshot lifecycle =
                InputBridgeDiagnostics.snapshot();
        final InputRelayRuntimeDiagnostics.Snapshot runtime =
                MagicDeskRuntime.captureInputRelayDiagnostics();
        final boolean touchpadRequested = runtime.displayId > 0
                && MagicDeskTouchpadActivity.isRequested(runtime.displayId);
        final boolean touchpadVisible = runtime.displayId > 0
                && MagicDeskTouchpadActivity.isVisible(runtime.displayId);

        int physicalMice = -1;
        int virtualMice = -1;
        int physicalKeyboards = -1;
        int virtualKeyboards = -1;
        Set<String> ownedPorts = new LinkedHashSet<>();
        Map<String, String> activeAssociations = new LinkedHashMap<>();
        Set<String> missingAssociations = new LinkedHashSet<>();
        Set<String> unexpectedAssociations = new LinkedHashSet<>();
        String inputStateError = "";
        if (!ShellAccess.isReady()) {
            inputStateError = "Shizuku runtime unavailable";
        } else {
            try {
                final String inputDump =
                        FrameworkInputSnapshotSource.readRemote();
                final List<DesktopMouseDevice> mice =
                        DesktopInputDeviceDiscovery.findRoutableMice(
                                inputDump);
                final List<DesktopKeyboardDevice> keyboards =
                        DesktopInputDeviceDiscovery.findRoutableKeyboards(
                                inputDump);
                virtualMice = countMousePorts(mice, true);
                physicalMice = mice.size() - virtualMice;
                virtualKeyboards = countKeyboardPorts(keyboards, true);
                physicalKeyboards = keyboards.size() - virtualKeyboards;
                ownedPorts = DesktopInputRoutingOwnership.read();
                final Map<String, String> allAssociations =
                        DesktopInputRoutingOwnership
                                .findActiveAssociationTargets(inputDump);
                final AssociationState associationState =
                        classifyAssociations(ownedPorts, allAssociations);
                activeAssociations = associationState.active;
                missingAssociations = associationState.missing;
                unexpectedAssociations = associationState.unexpected;
            } catch (IOException | RuntimeException error) {
                inputStateError = usefulMessage(error);
            }
        }
        return new InputRelayReportSnapshot(
                lifecycle,
                runtime,
                touchpadRequested,
                touchpadVisible,
                physicalMice,
                virtualMice,
                physicalKeyboards,
                virtualKeyboards,
                ownedPorts,
                activeAssociations,
                missingAssociations,
                unexpectedAssociations,
                inputStateError);
    }

    void appendReport(final StringBuilder report) {
        report.append("Input relay runtime: ")
                .append(lifecycle.reportLine())
                .append('\n')
                .append("Mouse relay snapshot: ")
                .append(runtime.mouse.reportLine())
                .append('\n')
                .append("Keyboard relay snapshot: ")
                .append(runtime.keyboard.reportLine())
                .append('\n')
                .append("Input routing snapshot: display=")
                .append(runtime.displayId)
                .append(", physicalMice=").append(countLabel(physicalMice))
                .append(", virtualMice=").append(countLabel(virtualMice))
                .append(", physicalKeyboards=")
                .append(countLabel(physicalKeyboards))
                .append(", virtualKeyboards=")
                .append(countLabel(virtualKeyboards))
                .append(", ownedPorts=").append(ownedPorts)
                .append(", activeAssociations=")
                .append(activeAssociations)
                .append(", missing=").append(missingAssociations)
                .append(", unexpected=").append(unexpectedAssociations);
        if (!inputStateError.isEmpty()) {
            report.append(", error=").append(inputStateError);
        }
        report.append('\n')
                .append("Pointer snapshot: provider=")
                .append(runtime.pointerProvider)
                .append(", display=").append(runtime.displayId)
                .append(", relayRequired=")
                .append(runtime.pointerRelayRequired)
                .append(", relayReady=")
                .append(runtime.pointerRelayReady)
                .append(", routingReady=")
                .append(runtime.pointerRoutingReady)
                .append(", position=")
                .append(pointLabel(runtime.pointerPosition))
                .append(", touchpadRequested=")
                .append(touchpadRequested)
                .append(", touchpadVisible=")
                .append(touchpadVisible)
                .append('\n');
    }

    private static int countMousePorts(
            final List<DesktopMouseDevice> mice,
            final boolean magicDesk) {
        int count = 0;
        for (final DesktopMouseDevice mouse : mice) {
            if (MAGICDESK_MOUSE_PORT.equals(mouse.location) == magicDesk) {
                count++;
            }
        }
        return count;
    }

    private static int countKeyboardPorts(
            final List<DesktopKeyboardDevice> keyboards,
            final boolean magicDesk) {
        int count = 0;
        for (final DesktopKeyboardDevice keyboard : keyboards) {
            if (keyboard.location.startsWith(
                    MAGICDESK_KEYBOARD_PREFIX) == magicDesk) {
                count++;
            }
        }
        return count;
    }

    static AssociationState classifyAssociations(
            final Set<String> ownedPorts,
            final Map<String, String> associations) {
        final Map<String, String> active = new LinkedHashMap<>();
        for (final String port : ownedPorts) {
            final String target = associations.get(port);
            if (target != null) {
                active.put(port, target);
            }
        }
        final Set<String> missing = new LinkedHashSet<>(ownedPorts);
        missing.removeAll(active.keySet());
        final Set<String> unexpected = new LinkedHashSet<>();
        for (final String port : associations.keySet()) {
            if (isMagicDeskVirtualPort(port) && !ownedPorts.contains(port)) {
                unexpected.add(port);
            }
        }
        return new AssociationState(active, missing, unexpected);
    }

    private static boolean isMagicDeskVirtualPort(final String port) {
        return MAGICDESK_MOUSE_PORT.equals(port)
                || port.startsWith(MAGICDESK_KEYBOARD_PREFIX);
    }

    static final class AssociationState {
        final Map<String, String> active;
        final Set<String> missing;
        final Set<String> unexpected;

        AssociationState(
                final Map<String, String> activeAssociations,
                final Set<String> missingAssociations,
                final Set<String> unexpectedAssociations) {
            active = activeAssociations;
            missing = missingAssociations;
            unexpected = unexpectedAssociations;
        }
    }

    private static String pointLabel(final Point point) {
        return point == null ? "unknown" : point.x + "," + point.y;
    }

    private static String countLabel(final int count) {
        return count < 0 ? "unknown" : Integer.toString(count);
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error == null ? null : error.getMessage();
        final String value = message == null || message.isEmpty()
                ? error == null ? "unknown" : error.getClass().getSimpleName()
                : message;
        final String normalized = value
                .replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 400
                ? normalized : normalized.substring(0, 400);
    }
}
