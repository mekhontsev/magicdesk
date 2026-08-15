package io.github.mekhontsev.magicdesk;

import android.content.ClipData;
import android.content.ClipDescription;
import android.net.Uri;
import android.view.DragEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Process-local file drag data shared by the desktop and Files windows. */
final class FileDragPayload {
    static final String MIME_TYPE =
            "application/vnd.io.github.mekhontsev.magicdesk.files";

    final List<String> absolutePaths;
    final String desktopItemId;
    final boolean copy;

    FileDragPayload(
            final List<String> absolutePaths,
            final String desktopItemId,
            final boolean copy) {
        if (absolutePaths == null || absolutePaths.isEmpty()) {
            throw new IllegalArgumentException("missing dragged paths");
        }
        this.absolutePaths = Collections.unmodifiableList(
                new ArrayList<>(absolutePaths));
        this.desktopItemId = desktopItemId;
        this.copy = copy;
    }

    static FileDragPayload from(final DragEvent event) {
        final Object state = event == null ? null : event.getLocalState();
        return state instanceof FileDragPayload
                ? (FileDragPayload) state : null;
    }

    ClipData clipData(
            final CharSequence label,
            final List<Uri> shareableUris) {
        final List<Uri> uris = shareableUris == null
                ? Collections.emptyList() : shareableUris;
        if (uris.isEmpty()) {
            return new ClipData(
                    label,
                    new String[]{MIME_TYPE},
                    new ClipData.Item(label));
        }
        final ClipData data = new ClipData(
                label,
                new String[]{
                        MIME_TYPE,
                        ClipDescription.MIMETYPE_TEXT_URILIST
                },
                new ClipData.Item(uris.get(0)));
        for (int index = 1; index < uris.size(); index++) {
            data.addItem(new ClipData.Item(uris.get(index)));
        }
        return data;
    }

    List<String> pathsForDestination(final String destination) {
        if (copy) {
            return absolutePaths;
        }
        final String normalizedDestination =
                ShellFilePathPolicy.normalizeShellAbsolute(destination);
        final List<String> paths = new ArrayList<>(absolutePaths.size());
        for (final String path : absolutePaths) {
            if (!normalizedDestination.equals(
                    ShellFilePathPolicy.shellParent(path))) {
                paths.add(path);
            }
        }
        return paths;
    }
}
