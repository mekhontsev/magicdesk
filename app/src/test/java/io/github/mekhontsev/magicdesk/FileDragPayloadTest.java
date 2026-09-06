package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.view.View;

import java.util.AbstractList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class FileDragPayloadTest {
    @Test
    public void externalDragsGrantReadOnlyAccess() {
        assertEquals(View.DRAG_FLAG_GLOBAL | View.DRAG_FLAG_GLOBAL_URI_READ,
                FileDragPayload.dragFlags(true));
    }

    @Test
    public void localFileAndFolderDragsCanCrossOnlyOurOwnWindows() {
        assertEquals(View.DRAG_FLAG_GLOBAL_SAME_APPLICATION, FileDragPayload.dragFlags(false));
    }

    @Test
    public void externalLimitIsCheckedBeforeTraversingTheUris() {
        final int count = AndroidContentPayload.MAX_URI_ITEMS + 1;
        final FileDragPayload payload = new FileDragPayload(
                Collections.nCopies(count, "/tmp/file"), null, false);
        final List<AndroidContentPayload.UriItem> uris = new AbstractList<>() {
            @Override
            public AndroidContentPayload.UriItem get(final int index) {
                throw new AssertionError("oversized URI list must not be traversed");
            }

            @Override
            public int size() {
                return count;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> payload.clipData("Files", uris));
    }

    @Test
    public void partialExternalSelectionIsRejectedBeforeCreatingClipData() {
        final FileDragPayload payload = new FileDragPayload(
                List.of("/tmp/file", "/tmp/folder"), null, false);

        assertThrows(IllegalArgumentException.class,
                () -> payload.clipData("Files", Collections.singletonList(null)));
    }

    @Test
    public void missingExternalUriCannotSilentlyBecomeALabelOnlyDrag() {
        final FileDragPayload payload = new FileDragPayload(
                List.of("/tmp/file"), null, false);

        assertThrows(IllegalArgumentException.class,
                () -> payload.clipData("Files", Collections.singletonList(null)));
    }

    @Test
    public void movingToTheCurrentParentSkipsOnlyNoOpEntries() {
        final FileDragPayload payload = new FileDragPayload(
                List.of(
                        "/storage/emulated/0/Download/one",
                        "/storage/emulated/0/Documents/two"),
                null,
                false);

        assertEquals(
                List.of("/storage/emulated/0/Documents/two"),
                payload.pathsForDestination(
                        "/storage/emulated/0/Download"));
    }

    @Test
    public void copyingToTheCurrentParentKeepsEntriesForDuplicateNaming() {
        final List<String> paths = List.of(
                "/storage/emulated/0/Download/one");
        final FileDragPayload payload = new FileDragPayload(
                paths,
                null,
                true);

        assertEquals(
                paths,
                payload.pathsForDestination(
                        "/storage/emulated/0/Download"));
    }
}
