package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class AndroidContentMimeTypesTest {
    @Test
    public void unknownUrisKeepTheExplicitShareType() {
        final var types = types(List.of("image/png"), List.of("*/*", "*/*"));
        assertEquals("image/png", types.preferred);
    }

    @Test
    public void descriptionDoesNotAddAContradictoryUnknownType() {
        final var types = types(List.of("image/png"), List.of("*/*"));
        assertEquals(List.of("image/png", "text/uri-list"), types.description);
    }

    @Test
    public void clipboardDescriptionPreservesTheTypeOnTheNextTransfer() {
        final var original = new AndroidContentMimeTypes(
                List.of("image/png"), List.of("*/*", "*/*"), true, true);
        final String uriType = AndroidContentMimeTypes.preferredUriMimeType(original.description);
        final var imported = types(original.description, List.of(uriType, uriType));
        assertEquals("image/png", imported.preferred);
        assertTrue(imported.description.contains("text/plain"));
        assertTrue(imported.description.contains("text/html"));
    }

    @Test
    public void explicitlyAmbiguousDescriptionStaysAmbiguous() {
        final var types = types(List.of("image/png", "*/*"), List.of("*/*"));
        assertEquals("*/*", types.preferred);
        assertTrue(types.description.contains("*/*"));
        assertEquals("*/*", AndroidContentMimeTypes.preferredUriMimeType(types.description));
    }

    @Test
    public void oneUnknownFileDoesNotInheritAnotherFilesType() {
        final var types = types(List.of("image/png"), List.of("image/png", "*/*"));
        assertEquals("*/*", types.preferred);
        assertTrue(types.description.contains("*/*"));
    }

    @Test
    public void mixedKnownFilesKeepAllTheirTypes() {
        final var types = types(List.of(), List.of("image/png", "application/pdf"));
        assertEquals("*/*", types.preferred);
        assertEquals(List.of("text/uri-list", "image/png", "application/pdf"), types.description);
    }

    @Test
    public void whollyUnknownSelectionStillAdvertisesWildcard() {
        final var types = types(List.of(), List.of("*/*"));
        assertEquals("*/*", types.preferred);
        assertEquals(List.of("text/uri-list", "*/*"), types.description);
    }

    @Test
    public void addedIntentDeclarationDoesNotInheritGeneratedTransportTypes() {
        final var clip = new AndroidContentMimeTypes(
                List.of(FileDragPayload.MIME_TYPE), List.of("*/*"), true, false);
        final var merged = types(clip.withDeclaration("image/png"), List.of("*/*"));
        assertEquals(List.of(FileDragPayload.MIME_TYPE, "image/png"), merged.declared);
        assertEquals("image/png", merged.preferred);
        assertFalse(merged.declared.contains("text/plain"));
        assertFalse(merged.declared.contains("text/uri-list"));
    }

    @Test
    public void normalizationOwnsAnImmutableSnapshot() {
        final var declared = new ArrayList<>(List.of(" IMAGE/PNG ", "image/png", " "));
        final var items = new ArrayList<>(List.of("image/png"));
        final var types = types(declared, items);
        declared.clear();
        items.clear();
        assertEquals(List.of("image/png"), types.declared);
        assertEquals(List.of("image/png", "text/uri-list"), types.description);
        assertEquals("image/png", types.preferred);
        assertThrows(UnsupportedOperationException.class, () -> types.declared.clear());
        assertThrows(UnsupportedOperationException.class, () -> types.description.clear());
        types.withDeclaration("text/plain").clear();
        assertEquals(List.of("image/png"), types.declared);
    }

    @Test
    public void localDragTransportIsNotAFileType() {
        final var types = types(List.of(FileDragPayload.MIME_TYPE), List.of("*/*"));
        assertEquals("*/*", types.preferred);
        assertTrue(types.description.contains(FileDragPayload.MIME_TYPE));
    }

    @Test
    public void fileDragKeepsKnownMimeAlongsideTheLocalTransport() {
        final var types = types(List.of(FileDragPayload.MIME_TYPE), List.of("image/png"));
        assertEquals("image/png", types.preferred);
        assertEquals(List.of(FileDragPayload.MIME_TYPE, "text/uri-list", "image/png"),
                types.description);
        assertEquals("image/png", AndroidContentMimeTypes.preferredUriMimeType(types.description));
    }

    private static AndroidContentMimeTypes types(
            final List<String> declared, final List<String> items) {
        return new AndroidContentMimeTypes(declared, items, false, false);
    }
}
