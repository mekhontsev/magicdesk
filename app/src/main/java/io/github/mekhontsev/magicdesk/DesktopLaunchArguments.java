package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded immutable arguments accompanying a desktop launch request. */
final class DesktopLaunchArguments {
    private static final int MAX_ARGUMENTS = 128;
    private static final DesktopLaunchArguments EMPTY =
            new DesktopLaunchArguments(List.of());

    final List<DesktopLaunchArgument> values;

    private DesktopLaunchArguments(
            final List<DesktopLaunchArgument> values) {
        requireCount(values.size());
        final List<DesktopLaunchArgument> copy =
                new ArrayList<>(values.size());
        for (final DesktopLaunchArgument value : values) {
            if (value == null) {
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
        if (paths == null || paths.isEmpty()) {
            return EMPTY;
        }
        requireCount(paths.size());
        final List<DesktopLaunchArgument> values = new ArrayList<>(paths.size());
        for (final String path : paths) {
            values.add(DesktopLaunchArgument.file(path));
        }
        return new DesktopLaunchArguments(values);
    }

    static DesktopLaunchArguments of(
            final List<DesktopLaunchArgument> values) {
        return values == null || values.isEmpty()
                ? EMPTY : new DesktopLaunchArguments(values);
    }

    static void requireCount(final int count) {
        if (count < 0 || count > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("invalid launch argument count");
        }
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
