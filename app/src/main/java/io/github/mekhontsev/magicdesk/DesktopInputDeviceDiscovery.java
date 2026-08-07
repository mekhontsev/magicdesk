package io.github.mekhontsev.magicdesk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DesktopInputDeviceDiscovery {
    private static final String DUMPSYS = "/system/bin/dumpsys";
    private static final int MAGICDESK_VENDOR_ID = 0x4d44;
    private static final int MAGICDESK_MOUSE_PRODUCT_ID = 0x0001;
    private static final Pattern EVENT_HUB_DEVICE =
            Pattern.compile("^\\s*-?\\d+:\\s+(.+)$");
    private static final Pattern INPUT_IDENTIFIER = Pattern.compile(
            ".*vendor=0x([0-9a-fA-F]+), product=0x([0-9a-fA-F]+).*");

    private DesktopInputDeviceDiscovery() {
    }

    static List<DesktopKeyboardDevice> findKeyboards()
            throws IOException, InterruptedException {
        final List<DeviceRecord> records = readEventHubDevices();
        return findKeyboards(records, false);
    }

    static List<DesktopKeyboardDevice> findRoutableKeyboards()
            throws IOException, InterruptedException {
        final List<DeviceRecord> records = readEventHubDevices();
        return findKeyboards(records, true);
    }

    static List<DesktopKeyboardDevice> findKeyboards(
            final String inputDump) throws IOException {
        try (BufferedReader reader =
                new BufferedReader(new StringReader(inputDump))) {
            return findKeyboards(readEventHubDevices(reader), false);
        }
    }

    static List<DesktopKeyboardDevice> findRoutableKeyboards(
            final String inputDump) throws IOException {
        try (BufferedReader reader =
                new BufferedReader(new StringReader(inputDump))) {
            return findKeyboards(readEventHubDevices(reader), true);
        }
    }

    static List<DesktopMouseDevice> findMice()
            throws IOException, InterruptedException {
        final List<DeviceRecord> records = readEventHubDevices();
        return findMice(records, false);
    }

    static List<DesktopMouseDevice> findRoutableMice()
            throws IOException, InterruptedException {
        final List<DeviceRecord> records = readEventHubDevices();
        return findMice(records, true);
    }

    static List<DesktopMouseDevice> findMice(final String inputDump)
            throws IOException {
        try (BufferedReader reader =
                new BufferedReader(new StringReader(inputDump))) {
            return findMice(readEventHubDevices(reader), false);
        }
    }

    static List<DesktopMouseDevice> findRoutableMice(
            final String inputDump) throws IOException {
        try (BufferedReader reader =
                new BufferedReader(new StringReader(inputDump))) {
            return findMice(readEventHubDevices(reader), true);
        }
    }

    private static List<DesktopMouseDevice> findMice(
            final List<DeviceRecord> records,
            final boolean includeMagicDeskMouse) {
        final List<DesktopMouseDevice> result = new ArrayList<>();
        for (final DeviceRecord record : records) {
            final boolean magicDeskMouse = isMagicDeskMouse(record);
            if (record.classes.contains("CURSOR")
                    && (record.classes.contains("EXTERNAL")
                            || (includeMagicDeskMouse && magicDeskMouse))
                    && (includeMagicDeskMouse || !magicDeskMouse)) {
                result.add(new DesktopMouseDevice(
                        record.path,
                        record.location,
                        record.vendorId,
                        record.productId));
            }
        }
        return result;
    }

    private static boolean isMagicDeskMouse(final DeviceRecord record) {
        return record.vendorId == MAGICDESK_VENDOR_ID
                && record.productId == MAGICDESK_MOUSE_PRODUCT_ID;
    }

    private static List<DesktopKeyboardDevice> findKeyboards(
            final List<DeviceRecord> records,
            final boolean includeMagicDeskKeyboard) {
        final List<DesktopKeyboardDevice> result = new ArrayList<>();
        for (final DeviceRecord record : records) {
            if (record.classes.contains("KEYBOARD")
                    && record.classes.contains("ALPHAKEY")
                    && record.classes.contains("EXTERNAL")
                    && (includeMagicDeskKeyboard
                            || !record.name.startsWith(
                                    "MagicDesk Keyboard"))) {
                result.add(new DesktopKeyboardDevice(
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
        String name = null;
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
                        name,
                        path,
                        location,
                        vendorId,
                        productId);
                break;
            }
            final Matcher deviceHeader =
                    EVENT_HUB_DEVICE.matcher(line);
            if (deviceHeader.matches()) {
                addRecord(
                        result,
                        classes,
                        name,
                        path,
                        location,
                        vendorId,
                        productId);
                classes = null;
                name = deviceHeader.group(1).trim();
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
            final String name,
            final String path,
            final String location,
            final int vendorId,
            final int productId) {
        if (classes == null || name == null
                || path == null || location == null
                || vendorId < 0 || productId < 0
                || !path.startsWith("/dev/input/event")
                || location.isEmpty()) {
            return;
        }
        result.add(new DeviceRecord(
                classes, name, path, location, vendorId, productId));
    }

    private static final class DeviceRecord {
        final String classes;
        final String name;
        final String path;
        final String location;
        final int vendorId;
        final int productId;

        DeviceRecord(
                final String classes,
                final String name,
                final String path,
                final String location,
                final int vendorId,
                final int productId) {
            this.classes = classes;
            this.name = name;
            this.path = path;
            this.location = location;
            this.vendorId = vendorId;
            this.productId = productId;
        }
    }
}
