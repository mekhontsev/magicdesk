package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public final class FileDragPayloadTest {
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
