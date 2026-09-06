package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.List;

public final class AndroidContentPolicyTest {
    @Test
    public void itemMimeTypeWinsOverTransportDeclarations() {
        assertEquals(
                "text/plain",
                new AndroidContentMimeTypes(
                        List.of("text/uri-list", "application/octet-stream"),
                        List.of("text/plain"),
                        false,
                        false).preferred);
    }

    @Test
    public void mixedUriMimeTypesUseWildcard() {
        assertEquals(
                "*/*",
                new AndroidContentMimeTypes(
                        List.of("text/uri-list"),
                        List.of("image/png", "image/jpeg"),
                        false,
                        false).preferred);
    }

    @Test
    public void unknownItemMustNotInheritAnotherFilesType() {
        assertEquals("*/*", new AndroidContentMimeTypes(
                List.of("image/png"), List.of("image/png", "*/*"), false, false).preferred);
    }

    @Test
    public void explicitWildcardDeclarationPreventsNarrowing() {
        assertEquals("*/*", new AndroidContentMimeTypes(
                List.of("image/png", "*/*"), List.of("*/*"), false, false).preferred);
    }

    @Test
    public void uniformDeclaredTypeCanDescribeOtherwiseUnknownItems() {
        assertEquals("image/png", new AndroidContentMimeTypes(
                List.of("text/uri-list", "image/png"), List.of("*/*", "*/*"), false, false).preferred);
    }

    @Test
    public void mixedClipDeclarationDoesNotAssignFirstTypeToEveryUri() {
        assertEquals("*/*", AndroidContentMimeTypes.preferredUriMimeType(
                List.of("text/uri-list", "image/png", "application/pdf")));
        assertEquals("*/*", AndroidContentMimeTypes.preferredUriMimeType(
                List.of("application/pdf", "image/png", "text/uri-list")));
    }

    @Test
    public void wildcardClipDeclarationDoesNotAssignKnownTypeToEveryUri() {
        assertEquals("*/*", AndroidContentMimeTypes.preferredUriMimeType(
                List.of("image/png", "*/*")));
        assertEquals("*/*", AndroidContentMimeTypes.preferredUriMimeType(
                List.of("*/*", "image/png")));
    }

    @Test
    public void uniformClipDeclarationIsNormalizedAndKeepsItsType() {
        assertEquals("image/png", AndroidContentMimeTypes.preferredUriMimeType(
                List.of(" TEXT/URI-LIST ", "image/png", " IMAGE/PNG ")));
    }

    @Test
    public void transportOnlyClipDoesNotGuessAFileType() {
        assertEquals("*/*", AndroidContentMimeTypes.preferredUriMimeType(
                List.of("text/plain", "text/html", "text/uri-list")));
        assertEquals("*/*", AndroidContentMimeTypes.preferredUriMimeType(List.of()));
    }

    @Test
    public void textPayloadKeepsHtmlSemantics() {
        assertEquals(
                "text/html",
                new AndroidContentMimeTypes(
                        List.of(), List.of(), false, true).preferred);
    }

    @Test
    public void clipboardTextFileNameUsesSubjectAndExtension() {
        assertEquals(
                "Meeting notes.txt",
                ContentUriTransfer.textFileName(
                        "Meeting notes", "Clipboard", false));
    }

    @Test
    public void invalidClipboardTextFileNameFallsBack() {
        assertEquals(
                "Clipboard text.html",
                ContentUriTransfer.textFileName(
                        "../", "", true));
    }
}
