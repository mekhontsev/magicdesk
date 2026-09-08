package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Crash journal for transient Android input associations, scoped to this boot. */
final class DesktopInputRoutingOwnership implements InputRoutingLease.Storage {
    private static final Path FILE = Path.of("/data/local/tmp/magicdesk-input-routing.json");
    private static final Path BOOT_ID = Path.of("/proc/sys/kernel/random/boot_id");
    private static final int MAX_ENTRIES = 64;
    private static final int MAX_BYTES = 128 * 1024;

    @Override
    public Map<String, InputRoutingLease.Entry> read() throws IOException {
        if (!Files.exists(FILE)) {
            return new LinkedHashMap<>();
        }
        if (Files.size(FILE) > MAX_BYTES) {
            throw new IOException("input association journal exceeds size limit");
        }
        return decode(new String(Files.readAllBytes(FILE), StandardCharsets.UTF_8), bootId());
    }

    @Override
    public void write(final Map<String, InputRoutingLease.Entry> entries) throws IOException {
        if (entries.isEmpty()) {
            Files.deleteIfExists(FILE);
            return;
        }
        final String encoded = encode(entries, bootId());
        final Path temporary = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
            output.write(encoded.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        Files.move(temporary, FILE, StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    static Set<String> ports() throws IOException {
        return new DesktopInputRoutingOwnership().read().keySet();
    }

    private static String bootId() throws IOException {
        return new String(Files.readAllBytes(BOOT_ID), StandardCharsets.UTF_8).trim();
    }

    static Map<String, InputRoutingLease.Entry> decode(final String json, final String bootId)
            throws IOException {
        try {
            final JSONObject document = new JSONObject(json);
            final Map<String, InputRoutingLease.Entry> entries = new LinkedHashMap<>();
            if (!bootId.equals(document.getString("bootId"))) {
                // InputManager's runtime maps never survive a system restart.
                return entries;
            }
            final JSONArray values = document.getJSONArray("ports");
            if (values.length() > MAX_ENTRIES) {
                throw new IOException("too many owned input ports");
            }
            for (int i = 0; i < values.length(); ++i) {
                final JSONObject value = values.getJSONObject(i);
                final String port = value.getString("port");
                final String target = value.getString("target");
                validate(port);
                validate(target);
                final String previousId = value.isNull("previousUniqueId") ? null
                        : value.getString("previousUniqueId");
                if (previousId != null) {
                    validate(previousId);
                }
                final Integer previousPort = value.isNull("previousDisplayPort") ? null
                        : value.getInt("previousDisplayPort");
                if (previousPort != null && (previousPort < 0 || previousPort > 255)) {
                    throw new IOException("invalid previous physical display port");
                }
                if (entries.put(port, new InputRoutingLease.Entry(target, previousId,
                        previousPort)) != null) {
                    throw new IOException("duplicate owned input port");
                }
            }
            return entries;
        } catch (JSONException error) {
            throw new IOException("invalid input association journal", error);
        }
    }

    static String encode(final Map<String, InputRoutingLease.Entry> entries, final String bootId)
            throws IOException {
        if (entries.size() > MAX_ENTRIES || bootId.isEmpty()) {
            throw new IOException("invalid input association journal state");
        }
        try {
            final JSONArray values = new JSONArray();
            for (final Map.Entry<String, InputRoutingLease.Entry> item : entries.entrySet()) {
                validate(item.getKey());
                final InputRoutingLease.Entry entry = item.getValue();
                validate(entry.target);
                values.put(new JSONObject().put("port", item.getKey()).put("target", entry.target)
                        .put("previousUniqueId", entry.previousUniqueId == null
                                ? JSONObject.NULL : entry.previousUniqueId)
                        .put("previousDisplayPort", entry.previousDisplayPort == null
                                ? JSONObject.NULL : entry.previousDisplayPort));
            }
            return new JSONObject().put("bootId", bootId).put("ports", values).toString();
        } catch (JSONException error) {
            throw new IOException("cannot encode input association journal", error);
        }
    }

    private static void validate(final String value) throws IOException {
        if (value == null || value.isEmpty() || value.length() > 256
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IOException("invalid input association identity");
        }
    }
}
