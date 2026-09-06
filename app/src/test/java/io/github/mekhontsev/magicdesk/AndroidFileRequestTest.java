package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import java.util.Collections;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public final class AndroidFileRequestTest {
    @Test
    public void sharePreservesTextIndentationAndTrailingNewlines() throws Exception {
        final String text = "\n    first line\n\tsecond line  \n";
        final AndroidContentPayload content = AndroidIntegrationGateway.sharePayload(
                new JSONObject().put("text", text), List.of());

        assertEquals(text, content.text);
        assertEquals("text/plain", content.preferredMimeType());
    }

    @Test
    public void whitespaceIsContentRatherThanAnEmptyRequest() throws Exception {
        assertEquals(" \t\n", AndroidIntegrationGateway.sharePayload(
                new JSONObject().put("text", " \t\n"), List.of()).text);
    }

    @Test
    public void missingTextAndFilesIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AndroidIntegrationGateway.sharePayload(new JSONObject(), List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> AndroidIntegrationGateway.sharePayload(null, List.of()));
    }

    @Test
    public void shareMetadataKeepsTheCommonPayloadRepresentation() throws Exception {
        final AndroidContentPayload content = AndroidIntegrationGateway.sharePayload(
                new JSONObject().put("text", "text").put("name", "Selection")
                        .put("subject", "Subject"), List.of());
        assertEquals("Selection", content.label);
        assertEquals("Subject", content.subject);
        assertEquals(AndroidContentPayload.Origin.APPLICATION, content.origin);
    }

    @Test
    public void absentFilesAreValidForTextOnlySharing() throws Exception {
        assertTrue(AndroidIntegrationGateway.shareFiles(null).isEmpty());
        assertTrue(AndroidIntegrationGateway.shareFiles(new JSONObject()).isEmpty());
        assertTrue(AndroidIntegrationGateway.shareFiles(new JSONObject()
                .put("files", JSONObject.NULL)).isEmpty());
    }

    @Test
    public void invalidFileArraysAreNotSilentlyIgnored() throws Exception {
        for (final Object invalid : new Object[] {"/tmp/file", 1, new JSONObject()}) {
            final JSONObject request = new JSONObject().put("text", "text").put("files", invalid);
            assertThrows(JSONException.class, () -> AndroidIntegrationGateway.shareFiles(request));
        }
    }

    @Test
    public void eachSourceIsValidatedBeforeMetadataOrUriPreparation() throws Exception {
        for (final Object invalid : new Object[] {"", "  ", JSONObject.NULL, 17}) {
            final JSONObject request = new JSONObject().put("files",
                    new JSONArray().put("/tmp/valid").put(invalid));
            assertThrows(IllegalArgumentException.class,
                    () -> AndroidIntegrationGateway.shareFiles(request));
        }
    }

    @Test
    public void sourceSnapshotPreservesOrdering() throws Exception {
        final JSONArray files = new JSONArray().put("/tmp/first").put("content://provider/second");
        final List<String> sources = AndroidIntegrationGateway.shareFiles(new JSONObject().put("files", files));
        files.put(0, "/tmp/replaced");
        assertEquals(List.of("/tmp/first", "content://provider/second"), sources);
    }

    @Test
    public void sourceCountUsesTheSharedContentLimit() throws Exception {
        final JSONArray files = new JSONArray(Collections.nCopies(
                AndroidContentPayload.MAX_URI_ITEMS, "/tmp/file"));
        final JSONObject request = new JSONObject().put("files", files);
        assertEquals(AndroidContentPayload.MAX_URI_ITEMS, AndroidIntegrationGateway.shareFiles(request).size());
        files.put("/tmp/one-too-many");
        assertThrows(IllegalArgumentException.class, () -> AndroidIntegrationGateway.shareFiles(request));
    }

    @Test
    public void fileOperationIsValidatedWithoutPreparingContent() throws Exception {
        assertEquals(Intent.ACTION_VIEW, AndroidIntegrationGateway.fileAction(new JSONObject()));
        assertEquals(Intent.ACTION_EDIT, AndroidIntegrationGateway.fileAction(
                new JSONObject().put("operation", "edit")));
        assertThrows(IllegalArgumentException.class, () -> AndroidIntegrationGateway.fileAction(
                new JSONObject().put("operation", "delete")));
    }
}
