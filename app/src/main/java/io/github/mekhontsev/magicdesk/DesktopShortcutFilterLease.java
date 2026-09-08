package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.provider.Settings;
import android.util.AtomicFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;

/** Shell-owned enablement of our key filter, preserving other accessibility services. */
final class DesktopShortcutFilterLease {
    private static final ComponentName COMPONENT = new ComponentName(
            BuildConfig.APPLICATION_ID, DesktopShortcutService.class.getName());
    private static final AtomicFile JOURNAL = new AtomicFile(
            new File("/data/local/tmp/magicdesk-shortcut-filter.json"));
    void acquire() throws IOException {
        release();
        final Set<String> services = readServices();
        final boolean added = services.add(COMPONENT.flattenToString());
        final String previous = get(Settings.Secure.ACCESSIBILITY_ENABLED);
        FileOutputStream stream = null;
        try {
            final JSONObject record = new JSONObject().put("added", added)
                    .put("previousEnabled", previous == null ? JSONObject.NULL : previous);
            stream = JOURNAL.startWrite();
            stream.write(record.toString().getBytes(StandardCharsets.UTF_8));
            JOURNAL.finishWrite(stream);
        } catch (Exception error) {
            if (stream != null) JOURNAL.failWrite(stream);
            throw new IOException("cannot journal shortcut filter ownership", error);
        }
        if (added) writeServices(services);
        put(Settings.Secure.ACCESSIBILITY_ENABLED, "1");
    }

    void release() throws IOException {
        if (!JOURNAL.getBaseFile().exists()) return;
        try {
            final byte[] bytes = JOURNAL.readFully();
            if (bytes.length > 4096) throw new IOException("oversized shortcut filter journal");
            final JSONObject record = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            final Set<String> services = readServices();
            if (record.getBoolean("added") && services.remove(COMPONENT.flattenToString())) {
                writeServices(services);
            }
            final boolean otherServices = services.stream().anyMatch(
                    value -> !COMPONENT.flattenToString().equals(value));
            final String current = get(Settings.Secure.ACCESSIBILITY_ENABLED);
            if (!otherServices && "1".equals(current)) {
                put(Settings.Secure.ACCESSIBILITY_ENABLED, record.isNull("previousEnabled")
                        ? null : record.getString("previousEnabled"));
            }
            JOURNAL.delete();
        } catch (Exception error) {
            throw new IOException("cannot restore shortcut filter settings", error);
        }
    }

    private Set<String> readServices() throws IOException {
        final Set<String> result = new LinkedHashSet<>();
        final String raw = get(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (raw != null) {
            for (final String value : raw.split(":")) {
                final ComponentName component = ComponentName.unflattenFromString(value);
                if (!value.isEmpty()) result.add(component == null
                        ? value : component.flattenToString());
            }
        }
        return result;
    }

    private void writeServices(final Set<String> services) throws IOException {
        put(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, String.join(":", services));
    }

    private void put(final String key, final String value) throws IOException {
        if (value == null) command("delete", "secure", key);
        else command("put", "secure", key, value);
    }

    private String get(final String key) throws IOException {
        final String value = command("get", "secure", key).trim();
        return "null".equals(value) ? null : value;
    }

    private String command(final String... args) throws IOException {
        // A UserService's ContentResolver can retain the APK's attribution.
        // Android's settings command creates its own UID-matched framework client.
        final java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("/system/bin/settings");
        java.util.Collections.addAll(command, args);
        try {
            final BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                    new ProcessBuilder(command).redirectErrorStream(true).start(),
                    5_000L, 16 * 1024);
            if (result.exitCode != 0 || result.truncated) {
                throw new IOException("shortcut settings command failed: " + result.output);
            }
            return result.output;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("shortcut settings command interrupted", error);
        }
    }
}
