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

final class DesktopInputRoutingOwnership {
    private static final File OWNERSHIP_FILE = new File(
            "/data/local/tmp/magicdesk-input-routing-ports");
    private static final Pattern RUNTIME_ASSOCIATION = Pattern.compile(
            "^\\s*port:\\s+(.+?)\\s+display:\\s+\\d+\\s*$");
    private static final Pattern UNIQUE_ID_ASSOCIATION = Pattern.compile(
            "^\\s*port:\\s+(.+?)\\s+uniqueId:\\s+.+?\\s*$");
    private static final int MAX_PORTS = 32;
    private static final int MAX_PORT_LENGTH = 256;

    private DesktopInputRoutingOwnership() {
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

    static Set<String> findActiveAssociations(final String inputDump)
            throws IOException {
        final Set<String> ports = new LinkedHashSet<>();
        boolean inOwnedAssociations = false;
        try (BufferedReader reader = new BufferedReader(
                new StringReader(inputDump))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                if ("Runtime Associations:".equals(trimmed)
                        || "Unique Id Associations:".equals(trimmed)) {
                    inOwnedAssociations = true;
                    continue;
                }
                if (!inOwnedAssociations) {
                    continue;
                }
                if (trimmed.endsWith(":")
                        && !trimmed.startsWith("port:")) {
                    inOwnedAssociations = false;
                    continue;
                }
                final Matcher association =
                        RUNTIME_ASSOCIATION.matcher(line);
                if (association.matches()) {
                    ports.add(association.group(1));
                    continue;
                }
                final Matcher uniqueIdAssociation =
                        UNIQUE_ID_ASSOCIATION.matcher(line);
                if (uniqueIdAssociation.matches()) {
                    ports.add(uniqueIdAssociation.group(1));
                }
            }
        }
        return ports;
    }

    private static boolean isValidPort(final String port) {
        return port != null
                && !port.isEmpty()
                && port.length() <= MAX_PORT_LENGTH
                && port.indexOf('\n') < 0
                && port.indexOf('\r') < 0;
    }
}
