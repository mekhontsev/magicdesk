package io.github.mekhontsev.magicdesk;

import android.content.ClipData;
import android.net.Uri;
import android.view.DragEvent;

import java.util.ArrayList;
import java.util.List;

/** Converts Android drag payloads at the UI boundary into launch arguments. */
final class DesktopDragLaunchArguments {
    private static final int MAX_ARGUMENTS = 128;

    private DesktopDragLaunchArguments() {
    }

    static DesktopLaunchArguments from(final DragEvent event) {
        final FileDragPayload payload = FileDragPayload.from(event);
        if (payload != null) {
            return DesktopLaunchArguments.files(payload.absolutePaths);
        }
        final ClipData data = event == null ? null : event.getClipData();
        if (data == null) {
            return DesktopLaunchArguments.empty();
        }
        final List<DesktopLaunchArgument> values = new ArrayList<>();
        for (int index = 0;
                index < data.getItemCount() && values.size() < MAX_ARGUMENTS;
                index++) {
            final Uri uri = data.getItemAt(index).getUri();
            if (uri != null) {
                values.add(DesktopLaunchArgument.uri(uri.toString()));
            }
        }
        return DesktopLaunchArguments.of(values);
    }
}
