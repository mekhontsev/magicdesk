package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.AbstractList;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class FileDragPayloadTest {
    @Test
    public void dragScopePreservesPrivatePayloadsOnEverySupportedSdk() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Build { static class VERSION { static int SDK_INT; } }
                static class View {
                    static final int DRAG_FLAG_GLOBAL = 256, DRAG_FLAG_GLOBAL_URI_READ = 1;
                    static final int DRAG_FLAG_GLOBAL_SAME_APPLICATION = 4096;
                }
                """ + RuntimeSourceFixture.methods("FileDragPayload", "dragFlags")
                        .replace("android.os.Build", "Build") + """
                public static void verify() {
                    for (int sdk : new int[]{34, 35, 36}) {
                        Build.VERSION.SDK_INT = sdk;
                        check(dragFlags(true) == 257, "shareable drag lost its read-only grant");
                        check(dragFlags(false) == (sdk >= 35 ? 4096 : 0), "private drag escaped scope");
                        check((dragFlags(false) & View.DRAG_FLAG_GLOBAL) == 0, "private drag exposed globally");
                    }
                }
                """);
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
