package io.github.mekhontsev.magicdesk;

import android.os.ParcelFileDescriptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded parser and writer for MagicDesk's Desktop Entry subset. */
final class DesktopEntryFile {
    private static final String HEADER = "[Desktop Entry]";
    private static final String EXTENSION = ".desktop";
    private static final int MAX_BYTES = 64 * 1024;

    private DesktopEntryFile() {
    }

    static DesktopEntry read(final DesktopFileInfo file) {
        if (file == null || !isCandidate(
                file.name, file.size, file.directory)) {
            return null;
        }
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                ShellAccess.openDesktopFile(file.relativePath))) {
            return resolve(parse(readUtf8(input)));
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    static DesktopEntry read(final ShellFileInfo file) {
        if (file == null || !isCandidate(
                file.name, file.size, file.directory)) {
            return null;
        }
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                ShellAccess.openVerifiedShellFile(file, "r"))) {
            return resolve(parse(readUtf8(input)));
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    static DesktopFileInfo createFolder(final ShellFileInfo target)
            throws IOException {
        if (target == null || !target.directory) {
            throw new IllegalArgumentException(
                    "shortcut target must be a directory");
        }
        final Path normalized =
                ShellFilePathPolicy.absolute(target.absolutePath);
        final String displayName = "/".equals(target.name)
                ? "Filesystem" : target.name;
        return create(
                displayName,
                encodeLink(displayName, normalized.toString()));
    }

    static DesktopFileInfo createApplication(
            final DesktopApplicationShortcut shortcut) throws IOException {
        if (shortcut == null) {
            throw new IllegalArgumentException("missing application shortcut");
        }
        return create(shortcut.name, encodeApplication(shortcut));
    }

    static DesktopFileInfo createWebLink(
            final String name, final String url) throws IOException {
        return create(name, encodeWebLink(name, url));
    }

    static DesktopEntry parse(final String encoded) {
        final Map<String, String> values = parseValues(encoded);
        if (values == null) {
            return null;
        }
        final String type = values.get("Type");
        if ("Link".equals(type)) {
            return parseLink(values);
        }
        if ("Application".equals(type)) {
            return parseApplication(values);
        }
        return null;
    }

    static String encodeLink(final String name, final String targetPath) {
        final String normalized =
                ShellFilePathPolicy.normalizeShellAbsolute(targetPath);
        final String url;
        try {
            url = new URI("file", null, normalized, null).toASCIIString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("invalid shortcut target", error);
        }
        return HEADER + "\n"
                + "Version=1.5\n"
                + "Type=Link\n"
                + "Name=" + escape(requireName(name)) + "\n"
                + "URL=" + url + "\n"
                + "Icon=folder\n";
    }

    static String encodeWebLink(final String name, final String url) {
        return HEADER + "\n"
                + "Version=1.5\n"
                + "Type=Link\n"
                + "Name=" + escape(requireName(name)) + "\n"
                + "URL=" + DesktopWebShortcut.normalizeUrl(url) + "\n"
                + "Icon=web-browser\n";
    }

    static String encodeApplication(
            final DesktopApplicationShortcut shortcut) {
        final StringBuilder encoded = new StringBuilder()
                .append(HEADER).append('\n')
                .append("Version=1.5\n")
                .append("Type=Application\n")
                .append("Name=").append(escape(shortcut.name)).append('\n');
        append(encoded, "Icon", shortcut.icon);
        append(encoded, "Exec", shortcut.exec);
        if (shortcut.terminal) {
            append(encoded, "Terminal", "true");
        }
        if (shortcut.launchTarget != null) {
            append(encoded, "X-MagicDesk-Package",
                    shortcut.launchTarget.packageName);
            append(encoded, "X-MagicDesk-Activity",
                    shortcut.launchTarget.activityClassName);
            append(encoded, "X-MagicDesk-Action",
                    shortcut.launchTarget.action);
        }
        append(encoded, "X-MagicDesk-Intent", shortcut.intentUri);
        append(encoded, "X-MagicDesk-WindowMode",
                shortcut.launchMode.wireName);
        if (shortcut.execBackend != DesktopExecBackend.SHELL) {
            append(encoded, "X-MagicDesk-ExecBackend",
                    shortcut.execBackend.wireName);
        }
        if (shortcut.defaultLaunch) {
            append(encoded, "X-MagicDesk-Default", "true");
        }
        return encoded.toString();
    }

    static String applicationExec(final String intentUri) {
        if (intentUri == null || intentUri.isEmpty()) {
            return "";
        }
        return "/system/bin/am start --user current \""
                + intentUri.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("`", "\\`")
                        .replace("$", "\\$")
                        .replace("%", "%%")
                + "\"";
    }

    static String shortcutFileName(final String displayName) {
        String stem = requireName(displayName)
                .replace('/', '_')
                .replace('\\', '_')
                .replace('\0', '_');
        if (".".equals(stem) || "..".equals(stem)) {
            stem = "Shortcut";
        }
        stem = DesktopPathPolicy.validateName(stem);
        return stem.toLowerCase(Locale.ROOT).endsWith(EXTENSION)
                ? stem : stem + EXTENSION;
    }

    private static DesktopFileInfo create(
            final String displayName,
            final String encoded) throws IOException {
        final ShellFileInfo created = ShellAccess.createAvailableShellEntry(
                ShellDesktopDirectory.ABSOLUTE_PATH,
                shortcutFileName(displayName),
                false);
        try (OutputStream output =
                     new ParcelFileDescriptor.AutoCloseOutputStream(
                             ShellAccess.openVerifiedShellFile(created, "w"))) {
            output.write(encoded.getBytes(StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException error) {
            try {
                ShellAccess.deleteDesktopEntry(created.name);
            } catch (IOException | RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        return ShellAccess.getDesktopFileInfo(created.name);
    }

    private static Map<String, String> parseValues(final String encoded) {
        if (encoded == null || encoded.length() > MAX_BYTES) {
            return null;
        }
        final Map<String, String> values = new LinkedHashMap<>();
        boolean inDesktopEntry = false;
        for (final String line : encoded.split("\\r?\\n", -1)) {
            final String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inDesktopEntry = HEADER.equals(trimmed);
                continue;
            }
            if (!inDesktopEntry) {
                continue;
            }
            final int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            final String key = line.substring(0, separator).trim();
            if (!values.containsKey(key)) {
                values.put(key, unescape(line.substring(separator + 1)));
            }
        }
        return values;
    }

    private static DesktopEntry parseLink(
            final Map<String, String> values) {
        final String name = values.get("Name");
        final String rawUrl = values.get("URL");
        if (!validName(name) || rawUrl == null) {
            return null;
        }
        final URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException | RuntimeException error) {
            return null;
        }
        if ("file".equalsIgnoreCase(uri.getScheme())) {
            if (uri.getRawAuthority() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null) {
                return null;
            }
            try {
                return new DesktopFolderShortcut(
                        name.trim(),
                        ShellFilePathPolicy.normalizeShellAbsolute(
                                uri.getPath()),
                        false);
            } catch (IllegalArgumentException error) {
                return null;
            }
        }
        try {
            return new DesktopWebShortcut(
                    name.trim(), value(values, "Icon"), rawUrl);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static DesktopApplicationShortcut parseApplication(
            final Map<String, String> values) {
        final String name = values.get("Name");
        final String intentUri = value(values, "X-MagicDesk-Intent");
        final String exec = value(values, "Exec");
        if (!validName(name) || (intentUri.isEmpty() && exec.isEmpty())) {
            return null;
        }
        AppLaunchTarget target = null;
        final String packageName = value(values, "X-MagicDesk-Package");
        final String activity = value(values, "X-MagicDesk-Activity");
        final String action = value(values, "X-MagicDesk-Action");
        try {
            if (!packageName.isEmpty()) {
                target = activity.isEmpty()
                        ? AppLaunchTarget.packageDefault(packageName)
                        : AppLaunchTarget.explicit(
                                packageName, activity, action);
            }
            return new DesktopApplicationShortcut(
                    name.trim(),
                    value(values, "Icon"),
                    exec,
                    target,
                    intentUri,
                    DesktopLaunchMode.parse(
                            values.get("X-MagicDesk-WindowMode")),
                    "true".equalsIgnoreCase(
                            value(values, "X-MagicDesk-Default")),
                    DesktopExecBackend.parse(
                            values.get("X-MagicDesk-ExecBackend")),
                    "true".equalsIgnoreCase(
                            value(values, "Terminal")));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static DesktopEntry resolve(final DesktopEntry entry) {
        if (!(entry instanceof DesktopFolderShortcut)) {
            return entry;
        }
        final DesktopFolderShortcut shortcut =
                (DesktopFolderShortcut) entry;
        boolean available = false;
        try {
            available = ShellAccess.getShellFileInfo(
                    shortcut.targetPath).directory;
        } catch (IOException | RuntimeException ignored) {
            // Broken links remain visible so they can be inspected or deleted.
        }
        return new DesktopFolderShortcut(
                shortcut.name, shortcut.targetPath, available);
    }

    private static boolean isCandidate(
            final String name,
            final long size,
            final boolean directory) {
        return !directory
                && size >= 0L
                && size <= MAX_BYTES
                && name != null
                && name.toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    private static String readUtf8(final InputStream input) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > MAX_BYTES) {
                throw new IOException("desktop entry is too large");
            }
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void append(
            final StringBuilder encoded,
            final String key,
            final String value) {
        if (value != null && !value.isEmpty()) {
            encoded.append(key).append('=').append(escape(value)).append('\n');
        }
    }

    private static String value(
            final Map<String, String> values,
            final String key) {
        final String value = values.get(key);
        return value == null ? "" : value;
    }

    private static String requireName(final String value) {
        if (!validName(value)) {
            throw new IllegalArgumentException("missing desktop entry name");
        }
        return value.trim();
    }

    private static boolean validName(final String value) {
        return value != null
                && !value.trim().isEmpty()
                && value.indexOf('\0') < 0;
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String unescape(final String value) {
        final StringBuilder result = new StringBuilder(value.length());
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (!escaped && character == '\\') {
                escaped = true;
                continue;
            }
            if (!escaped) {
                result.append(character);
                continue;
            }
            switch (character) {
                case 'n':
                    result.append('\n');
                    break;
                case 'r':
                    result.append('\r');
                    break;
                case 't':
                    result.append('\t');
                    break;
                case 's':
                    result.append(' ');
                    break;
                default:
                    result.append(character);
                    break;
            }
            escaped = false;
        }
        if (escaped) {
            result.append('\\');
        }
        return result.toString();
    }
}
