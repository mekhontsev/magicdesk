package io.github.mekhontsev.magicdesk;

import android.view.DragEvent;

import java.util.ArrayList;
import java.util.List;

/** Converts Android drag payloads at the UI boundary into launch arguments. */
final class DesktopDragLaunchArguments {
    private DesktopDragLaunchArguments() {
    }

    static DesktopLaunchArguments from(final DragEvent event) {
        final FileDragPayload payload = FileDragPayload.from(event);
        if (payload != null) {
            return DesktopLaunchArguments.files(payload.absolutePaths);
        }
        final AndroidContentPayload content =
                AndroidContentPayload.fromClipData(
                        event == null ? null : event.getClipData(),
                        AndroidContentPayload.Origin.DRAG);
        if (!content.hasUris()) {
            return DesktopLaunchArguments.empty();
        }
        final List<DesktopLaunchArgument> values = new ArrayList<>();
        for (final AndroidContentPayload.UriItem item : content.uriItems) {
            values.add(DesktopLaunchArgument.uri(item.uri.toString()));
        }
        return DesktopLaunchArguments.of(values);
    }
}
