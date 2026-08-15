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

/** Safe Type=Link subset of the freedesktop desktop-entry format. */
final class DesktopFolderShortcutFile {
    private static final String HEADER = "[Desktop Entry]";
    private static final String EXTENSION = ".desktop";
    private static final int MAX_BYTES = 64 * 1024;

    private DesktopFolderShortcutFile() {
    }

    static DesktopFolderShortcut read(final DesktopFileInfo file) {
        if (file == null || !isCandidate(
                file.name, file.size, file.directory)) {
            return null;
        }
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                ShellAccess.openDesktopFile(file.relativePath))) {
            return resolve(readUtf8(input));
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    static DesktopFolderShortcut read(final ShellFileInfo file) {
        if (file == null || !isCandidate(
                file.name, file.size, file.directory)) {
            return null;
        }
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(
                ShellAccess.openVerifiedShellFile(file, "r"))) {
            return resolve(readUtf8(input));
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    static DesktopFileInfo create(final ShellFileInfo target)
            throws IOException {
        if (target == null || !target.directory) {
            throw new IllegalArgumentException("shortcut target must be a directory");
        }
        final Path normalized = ShellFilePathPolicy.absolute(target.absolutePath);
        final String displayName = "/".equals(target.name)
                ? "Filesystem" : target.name;
        final String requestedName = shortcutFileName(displayName);
        final ShellFileInfo created = ShellAccess.createAvailableShellEntry(
                ShellDesktopDirectory.ABSOLUTE_PATH,
                requestedName,
                false);
        try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(
                ShellAccess.openVerifiedShellFile(created, "w"))) {
            output.write(encode(displayName, normalized.toString())
                    .getBytes(StandardCharsets.UTF_8));
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

    static DesktopFolderShortcut parse(final String encoded) {
        if (encoded == null || encoded.length() > MAX_BYTES) {
            return null;
        }
        final Map<String, String> values = new LinkedHashMap<>();
        boolean inDesktopEntry = false;
        final String[] lines = encoded.split("\\r?\\n", -1);
        for (final String line : lines) {
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
        if (!"Link".equals(values.get("Type"))) {
            return null;
        }
        final String name = values.get("Name");
        final String rawUrl = values.get("URL");
        if (name == null || name.trim().isEmpty()
                || name.indexOf('\0') >= 0 || rawUrl == null) {
            return null;
        }
        final URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException | RuntimeException error) {
            return null;
        }
        if (!"file".equalsIgnoreCase(uri.getScheme())
                || uri.getRawAuthority() != null
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null) {
            return null;
        }
        final String path = uri.getPath();
        try {
            return new DesktopFolderShortcut(
                    name.trim(),
                    ShellFilePathPolicy.absolute(path).toString(),
                    false);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    static String encode(final String name, final String targetPath) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("missing shortcut name");
        }
        final String normalized = ShellFilePathPolicy.absolute(
                targetPath).toString();
        final String url;
        try {
            url = new URI("file", null, normalized, null).toASCIIString();
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("invalid shortcut target", error);
        }
        return HEADER + "\n"
                + "Version=1.5\n"
                + "Type=Link\n"
                + "Name=" + escape(name.trim()) + "\n"
                + "URL=" + url + "\n"
                + "Icon=folder\n";
    }

    static String shortcutFileName(final String displayName) {
        final String stem = DesktopPathPolicy.validateName(displayName);
        return stem.toLowerCase(Locale.ROOT).endsWith(EXTENSION)
                ? stem : stem + EXTENSION;
    }

    private static boolean isCandidate(
            final String name, final long size, final boolean directory) {
        return !directory
                && size >= 0L
                && size <= MAX_BYTES
                && name != null
                && name.toLowerCase(Locale.ROOT).endsWith(EXTENSION);
    }

    private static DesktopFolderShortcut resolve(final String encoded) {
        final DesktopFolderShortcut parsed = parse(encoded);
        if (parsed == null) {
            return null;
        }
        boolean available = false;
        try {
            available = ShellAccess.getShellFileInfo(
                    parsed.targetPath).directory;
        } catch (IOException | RuntimeException ignored) {
            // Broken links remain visible so the user can inspect or delete them.
        }
        return new DesktopFolderShortcut(
                parsed.name, parsed.targetPath, available);
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
            if (escaped) {
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
            } else {
                result.append(character);
            }
        }
        if (escaped) {
            result.append('\\');
        }
        return result.toString();
    }
}
