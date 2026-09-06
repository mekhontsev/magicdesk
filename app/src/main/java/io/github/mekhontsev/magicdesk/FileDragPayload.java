package io.github.mekhontsev.magicdesk;

import android.content.ClipData;
import android.view.DragEvent;
import android.view.View;

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
            final List<AndroidContentPayload.UriItem> shareableItems) {
        if (shareableItems != null && !shareableItems.isEmpty()) {
            if (shareableItems.size() > AndroidContentPayload.MAX_URI_ITEMS) {
                throw new IllegalArgumentException("too many dragged URIs");
            }
            if (shareableItems.size() != absolutePaths.size()) {
                throw new IllegalArgumentException("drag URIs must cover the entire selection");
            }
            for (final AndroidContentPayload.UriItem item : shareableItems) {
                if (item == null) {
                    throw new IllegalArgumentException("missing dragged URI");
                }
            }
        }
        return AndroidContentPayload.drag(
                label, shareableItems, MIME_TYPE).toClipData();
    }

    static int dragFlags(final boolean shareableContent) {
        return shareableContent
                ? View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ
                : View.DRAG_FLAG_GLOBAL_SAME_APPLICATION;
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
