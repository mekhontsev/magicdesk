package io.github.mekhontsev.magicdesk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ConsoleInputDeviceDiscovery {
    private static final String DUMPSYS = "/system/bin/dumpsys";
    private static final Pattern EVENT_HUB_DEVICE =
            Pattern.compile("^\\s*-?\\d+:\\s+.+$");
    private static final Pattern INPUT_IDENTIFIER = Pattern.compile(
            ".*vendor=0x([0-9a-fA-F]+), product=0x([0-9a-fA-F]+).*");

    private ConsoleInputDeviceDiscovery() {
    }

    static List<ConsoleKeyboardDevice> findKeyboards()
            throws IOException, InterruptedException {
        final List<DeviceRecord> records = readEventHubDevices();
        final List<ConsoleKeyboardDevice> result = new ArrayList<>();
        for (final DeviceRecord record : records) {
            if (record.classes.contains("KEYBOARD")
                    && record.classes.contains("ALPHAKEY")
                    && record.classes.contains("EXTERNAL")) {
                result.add(new ConsoleKeyboardDevice(
                        record.path,
                        record.location,
                        record.vendorId,
                        record.productId));
            }
        }
        return result;
    }

    static List<ConsoleMouseDevice> findMice()
            throws IOException, InterruptedException {
        final List<DeviceRecord> records = readEventHubDevices();
        return findMice(records);
    }

    static List<ConsoleMouseDevice> findMice(final String inputDump)
            throws IOException {
        try (BufferedReader reader =
                new BufferedReader(new StringReader(inputDump))) {
            return findMice(readEventHubDevices(reader));
        }
    }

    private static List<ConsoleMouseDevice> findMice(
            final List<DeviceRecord> records) {
        final List<ConsoleMouseDevice> result = new ArrayList<>();
        for (final DeviceRecord record : records) {
            if (record.classes.contains("CURSOR")
                    && record.classes.contains("EXTERNAL")) {
                result.add(new ConsoleMouseDevice(
                        record.path,
                        record.location,
                        record.vendorId,
                        record.productId));
            }
        }
        return result;
    }

    private static List<DeviceRecord> readEventHubDevices()
            throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(DUMPSYS, "input")
                .redirectErrorStream(true)
                .start();
        final List<DeviceRecord> result;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            result = readEventHubDevices(reader);
        }
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException(
                    "dumpsys input failed with exit code " + exitCode);
        }
        return result;
    }

    private static List<DeviceRecord> readEventHubDevices(
            final BufferedReader reader) throws IOException {
        final List<DeviceRecord> result = new ArrayList<>();
        boolean inEventHub = false;
        String classes = null;
        String path = null;
        String location = null;
        int vendorId = -1;
        int productId = -1;
        String line;
        while ((line = reader.readLine()) != null) {
            final String trimmed = line.trim();
            if ("Event Hub State:".equals(trimmed)) {
                inEventHub = true;
                continue;
            }
            if (!inEventHub) {
                continue;
            }
            if (trimmed.startsWith("Input Reader State")) {
                addRecord(
                        result,
                        classes,
                        path,
                        location,
                        vendorId,
                        productId);
                break;
            }
            if (EVENT_HUB_DEVICE.matcher(line).matches()) {
                addRecord(
                        result,
                        classes,
                        path,
                        location,
                        vendorId,
                        productId);
                classes = null;
                path = null;
                location = null;
                vendorId = -1;
                productId = -1;
                continue;
            }
            if (trimmed.startsWith("Classes:")) {
                classes = trimmed.substring("Classes:".length()).trim();
            } else if (trimmed.startsWith("Path:")) {
                path = trimmed.substring("Path:".length()).trim();
            } else if (trimmed.startsWith("Location:")) {
                location = trimmed.substring("Location:".length()).trim();
            } else if (trimmed.startsWith("Identifier:")) {
                final Matcher identifier =
                        INPUT_IDENTIFIER.matcher(trimmed);
                if (identifier.matches()) {
                    vendorId = Integer.parseInt(identifier.group(1), 16);
                    productId = Integer.parseInt(identifier.group(2), 16);
                }
            }
        }
        return result;
    }

    private static void addRecord(
            final List<DeviceRecord> result,
            final String classes,
            final String path,
            final String location,
            final int vendorId,
            final int productId) {
        if (classes == null || path == null || location == null
                || vendorId < 0 || productId < 0
                || !path.startsWith("/dev/input/event")
                || location.isEmpty()) {
            return;
        }
        result.add(new DeviceRecord(
                classes, path, location, vendorId, productId));
    }

    private static final class DeviceRecord {
        final String classes;
        final String path;
        final String location;
        final int vendorId;
        final int productId;

        DeviceRecord(
                final String classes,
                final String path,
                final String location,
                final int vendorId,
                final int productId) {
            this.classes = classes;
            this.path = path;
            this.location = location;
            this.vendorId = vendorId;
            this.productId = productId;
        }
    }
}
