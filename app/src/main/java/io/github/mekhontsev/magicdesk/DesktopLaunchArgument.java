package io.github.mekhontsev.magicdesk;

import java.net.URI;
import java.net.URISyntaxException;

/** One file or URI supplied to a Desktop Entry Exec template. */
final class DesktopLaunchArgument {
    final String path;
    final String uri;

    private DesktopLaunchArgument(
            final String path, final String uri) {
        if ((path == null || path.isEmpty())
                && (uri == null || uri.isEmpty())) {
            throw new IllegalArgumentException("empty desktop launch argument");
        }
        this.path = path == null ? "" : path;
        this.uri = uri == null ? "" : uri;
    }

    static DesktopLaunchArgument file(final String absolutePath) {
        final String path = ShellFilePathPolicy.normalizeShellAbsolute(
                absolutePath);
        try {
            return new DesktopLaunchArgument(
                    path,
                    new URI("file", "", path, null).toASCIIString());
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("invalid file argument", error);
        }
    }

    static DesktopLaunchArgument uri(final String value) {
        if (value == null || value.isEmpty() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid URI argument");
        }
        return new DesktopLaunchArgument("", value);
    }
}
