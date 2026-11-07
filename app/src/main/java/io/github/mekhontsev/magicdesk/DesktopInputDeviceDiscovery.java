package io.github.mekhontsev.magicdesk;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class DesktopInputDeviceDiscovery {
    private static final int MAGICDESK_VENDOR_ID = 0x4d44;
    private static final int MAGICDESK_MOUSE_PRODUCT_ID = 0x0001;
    private static final Pattern EVENT_HUB_DEVICE =
            Pattern.compile("^\\s*(-?\\d+):\\s+(.+)$");
    private static final Pattern INPUT_READER_DEVICE =
            Pattern.compile("^Device -?\\d+:.*$");
    private static final Pattern EVENT_HUB_IDS =
            Pattern.compile("EventHub Devices:\\s*\\[([^]]*)\\]");
    private static final Pattern INPUT_IDENTIFIER = Pattern.compile(
            ".*vendor=0x([0-9a-fA-F]+), product=0x([0-9a-fA-F]+).*");

    private DesktopInputDeviceDiscovery() {
    }

    static List<DesktopKeyboardDevice> findKeyboards(
            final String inputDump) throws IOException {
        try (BufferedReader reader =
                new BufferedReader(new StringReader(inputDump))) {
            return findKeyboards(readEventHubDevices(reader));
        }
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

    static List<DesktopTouchscreenDevice> findTouchscreens(final String inputDump)
            throws IOException {
        try (BufferedReader reader = new BufferedReader(new StringReader(inputDump))) {
            final List<DeviceRecord> candidates = readEventHubDevices(reader);
            candidates.removeIf(record -> !record.classes.contains("EXTERNAL")
                    || !record.classes.contains("TOUCH")
                    || record.classes.contains("TOUCHPAD")
                    || record.classes.contains("VIRTUAL"));
            final List<DesktopTouchscreenDevice> result = new ArrayList<>();
            if (candidates.isEmpty()) return result;
            final Set<String> touchscreenIds = readTouchscreenIds(reader);
            for (final DeviceRecord record : candidates) {
                if (touchscreenIds.contains(record.id)) {
                    result.add(new DesktopTouchscreenDevice(record.path, record.location,
                            record.vendorId, record.productId));
                }
            }
            return result;
        }
    }

    private static Set<String> readTouchscreenIds(final BufferedReader reader) throws IOException {
        final Set<String> result = new HashSet<>();
        List<String> eventHubIds = List.of();
        boolean external = false;
        boolean touchscreen = false;
        String line;
        // TOUCH includes touchpads. Join the reader's direct-touch classification
        // by raw IDs, including devices currently disabled by a missing viewport.
        while ((line = reader.readLine()) != null) {
            final String trimmed = line.trim();
            final boolean end = "Input Dispatcher State:".equals(trimmed);
            if (end || INPUT_READER_DEVICE.matcher(trimmed).matches()) {
                if (external && touchscreen) result.addAll(eventHubIds);
                if (end) return result;
                eventHubIds = List.of();
                external = false;
                touchscreen = false;
            } else {
                final Matcher ids = EVENT_HUB_IDS.matcher(trimmed);
                if (ids.matches()) {
                    eventHubIds = Arrays.asList(ids.group(1).trim().split("\\s+"));
                } else if ("IsExternal: true".equals(trimmed)) {
                    external = true;
                } else if ("DeviceType: TOUCH_SCREEN".equals(trimmed)) {
                    touchscreen = true;
                }
            }
        }
        throw new IOException("incomplete InputReader touch inventory");
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
            final List<DeviceRecord> records) {
        final List<DesktopKeyboardDevice> result = new ArrayList<>();
        for (final DeviceRecord record : records) {
            if (record.classes.contains("KEYBOARD")
                    && record.classes.contains("ALPHAKEY")
                    && record.classes.contains("EXTERNAL")) {
                result.add(new DesktopKeyboardDevice(
                        record.path,
                        record.location,
                        record.vendorId,
                        record.productId));
            }
        }
        return result;
    }

    private static List<DeviceRecord> readEventHubDevices(
            final BufferedReader reader) throws IOException {
        final List<DeviceRecord> result = new ArrayList<>();
        boolean inEventHub = false;
        String id = null;
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
                        id,
                        classes,
                        name,
                        path,
                        location,
                        vendorId,
                        productId);
                return result;
            }
            final Matcher deviceHeader =
                    EVENT_HUB_DEVICE.matcher(line);
            if (deviceHeader.matches()) {
                addRecord(
                        result,
                        id,
                        classes,
                        name,
                        path,
                        location,
                        vendorId,
                        productId);
                classes = null;
                id = deviceHeader.group(1);
                name = deviceHeader.group(2).trim();
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
        throw new IOException("incomplete EventHub inventory");
    }

    private static void addRecord(
            final List<DeviceRecord> result,
            final String id,
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
                id, classes, name, path, location, vendorId, productId));
    }

    private static final class DeviceRecord {
        final String id;
        final Set<String> classes;
        final String name;
        final String path;
        final String location;
        final int vendorId;
        final int productId;

        DeviceRecord(
                final String id,
                final String classes,
                final String name,
                final String path,
                final String location,
                final int vendorId,
                final int productId) {
            this.id = id;
            this.classes = Set.copyOf(Arrays.asList(classes.split("\\s*\\|\\s*")));
            this.name = name;
            this.path = path;
            this.location = location;
            this.vendorId = vendorId;
            this.productId = productId;
        }
    }
}
