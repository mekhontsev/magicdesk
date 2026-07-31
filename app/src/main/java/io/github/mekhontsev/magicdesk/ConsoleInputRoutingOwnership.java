package io.github.mekhontsev.magicdesk;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ConsoleInputRoutingOwnership {
    static final String SHIZUKU_KEYBOARD_LOCATION =
            "magicdesk-shizuku-keyboard";
    private static final String SHIZUKU_KEYBOARD_LOCATION_PREFIX =
            SHIZUKU_KEYBOARD_LOCATION + "-";

    private static final File OWNERSHIP_FILE = new File(
            "/data/local/tmp/magicdesk-input-routing-ports");
    private static final Pattern RUNTIME_ASSOCIATION = Pattern.compile(
            "^\\s*port:\\s+(.+?)\\s+display:\\s+\\d+\\s*$");
    private static final int MAX_PORTS = 32;
    private static final int MAX_PORT_LENGTH = 256;

    private ConsoleInputRoutingOwnership() {
    }

    static void record(final Set<String> ports) throws IOException {
        final File temporary = new File(
                OWNERSHIP_FILE.getParentFile(),
                OWNERSHIP_FILE.getName() + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(temporary, false),
                StandardCharsets.UTF_8)) {
            for (final String port : ports) {
                if (isValidPort(port)) {
                    writer.write(port);
                    writer.write('\n');
                }
            }
        }
        if (OWNERSHIP_FILE.exists() && !OWNERSHIP_FILE.delete()) {
            temporary.delete();
            throw new IOException(
                    "failed to replace input routing ownership file");
        }
        if (!temporary.renameTo(OWNERSHIP_FILE)) {
            temporary.delete();
            throw new IOException(
                    "failed to publish input routing ownership file");
        }
    }

    static Set<String> read() throws IOException {
        final Set<String> ports = new LinkedHashSet<>();
        if (!OWNERSHIP_FILE.isFile()) {
            return ports;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        new FileInputStream(OWNERSHIP_FILE),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null
                    && ports.size() < MAX_PORTS) {
                final String port = line.trim();
                if (isValidPort(port)) {
                    ports.add(port);
                }
            }
        }
        return ports;
    }

    static void clear() throws IOException {
        if (OWNERSHIP_FILE.exists() && !OWNERSHIP_FILE.delete()) {
            throw new IOException(
                    "failed to clear input routing ownership file");
        }
    }

    static Set<String> findRuntimeAssociations(final String inputDump)
            throws IOException {
        final Set<String> ports = new LinkedHashSet<>();
        boolean inRuntimeAssociations = false;
        try (BufferedReader reader = new BufferedReader(
                new StringReader(inputDump))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                if ("Runtime Associations:".equals(trimmed)) {
                    inRuntimeAssociations = true;
                    continue;
                }
                if (!inRuntimeAssociations) {
                    continue;
                }
                if (trimmed.endsWith(":")
                        && !trimmed.startsWith("port:")) {
                    break;
                }
                final Matcher association =
                        RUNTIME_ASSOCIATION.matcher(line);
                if (association.matches()) {
                    ports.add(association.group(1));
                }
            }
        }
        return ports;
    }

    static Set<String> findLegacyOwnedPorts(final String inputDump)
            throws IOException {
        final Set<String> runtimeAssociations =
                findRuntimeAssociations(inputDump);
        final Set<String> ownedPorts = new LinkedHashSet<>();
        boolean markerFound = false;
        for (final String port : runtimeAssociations) {
            if (isMagicDeskKeyboardPort(port)) {
                markerFound = true;
                break;
            }
        }
        if (!markerFound) {
            return ownedPorts;
        }
        for (final ConsoleKeyboardDevice keyboard
                : ConsoleInputDeviceDiscovery.findRoutableKeyboards(
                        inputDump)) {
            if (runtimeAssociations.contains(keyboard.location)) {
                ownedPorts.add(keyboard.location);
            }
        }
        for (final ConsoleMouseDevice mouse
                : ConsoleInputDeviceDiscovery.findMice(inputDump)) {
            if (runtimeAssociations.contains(mouse.location)) {
                ownedPorts.add(mouse.location);
            }
        }
        for (final String port : runtimeAssociations) {
            if (isMagicDeskKeyboardPort(port)) {
                ownedPorts.add(port);
            }
        }
        return ownedPorts;
    }

    private static boolean isMagicDeskKeyboardPort(
            final String port) {
        return SHIZUKU_KEYBOARD_LOCATION.equals(port)
                || port.startsWith(
                        SHIZUKU_KEYBOARD_LOCATION_PREFIX);
    }

    private static boolean isValidPort(final String port) {
        return port != null
                && !port.isEmpty()
                && port.length() <= MAX_PORT_LENGTH
                && port.indexOf('\n') < 0
                && port.indexOf('\r') < 0;
    }
}
