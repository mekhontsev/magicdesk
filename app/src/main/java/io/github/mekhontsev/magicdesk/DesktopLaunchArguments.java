package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded immutable arguments accompanying a desktop launch request. */
final class DesktopLaunchArguments {
    private static final int MAX_ARGUMENTS = 128;
    private static final int MAX_ARGUMENT_LENGTH = 8192;
    private static final DesktopLaunchArguments EMPTY =
            new DesktopLaunchArguments(List.of());

    final List<DesktopLaunchArgument> values;

    private DesktopLaunchArguments(
            final List<DesktopLaunchArgument> values) {
        if (values == null || values.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("too many launch arguments");
        }
        final List<DesktopLaunchArgument> copy =
                new ArrayList<>(values.size());
        for (final DesktopLaunchArgument value : values) {
            if (value == null
                    || value.path.length() > MAX_ARGUMENT_LENGTH
                    || value.uri.length() > MAX_ARGUMENT_LENGTH) {
                throw new IllegalArgumentException("invalid launch argument");
            }
            copy.add(value);
        }
        this.values = Collections.unmodifiableList(copy);
    }

    static DesktopLaunchArguments empty() {
        return EMPTY;
    }

    static DesktopLaunchArguments files(final List<String> paths) {
        final List<DesktopLaunchArgument> values = new ArrayList<>();
        if (paths != null) {
            for (final String path : paths) {
                values.add(DesktopLaunchArgument.file(path));
            }
        }
        return values.isEmpty() ? EMPTY : new DesktopLaunchArguments(values);
    }

    static DesktopLaunchArguments of(
            final List<DesktopLaunchArgument> values) {
        return values == null || values.isEmpty()
                ? EMPTY : new DesktopLaunchArguments(values);
    }

    boolean isEmpty() {
        return values.isEmpty();
    }

    List<String> filePaths() {
        final List<String> paths = new ArrayList<>();
        for (final DesktopLaunchArgument value : values) {
            if (!value.path.isEmpty()) {
                paths.add(value.path);
            }
        }
        return paths;
    }

    List<String> uris() {
        final List<String> uris = new ArrayList<>(values.size());
        for (final DesktopLaunchArgument value : values) {
            if (!value.uri.isEmpty()) {
                uris.add(value.uri);
            }
        }
        return uris;
    }
}
