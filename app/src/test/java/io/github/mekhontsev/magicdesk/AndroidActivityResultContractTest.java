package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class AndroidActivityResultContractTest {
    @Test
    public void consumingAResultReleasesGrantsBeforeSerializingTheResponse() throws Exception {
        final String store = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/AndroidActivityResultStore.java"));
        final String get = store.substring(store.indexOf("static JSONObject get("),
                store.indexOf("private static ArrayList<Entry> trimLocked()"));
        final int release = get.indexOf("consumed ? releaseEntry(entry) : null");
        assertTrue(release >= 0);
        assertTrue(get.indexOf("entry.toJson()") > release);
    }

    @Test
    public void grantsUseValidatedResultUrisInsteadOfReadingTheIntentAgain() throws Exception {
        final String store = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/AndroidActivityResultStore.java"));
        final String complete = store.substring(store.indexOf("static void complete("),
                store.indexOf("static void fail("));
        final int read = complete.indexOf("AndroidActivityResultData.read(data)");
        final int persist = complete.indexOf("persistReturnedUris(context, result, grants)");
        assertTrue(read >= 0);
        assertTrue(persist > read);
        assertTrue(complete.contains("catch (JSONException | RuntimeException error)"));
        assertTrue(complete.contains("releaseGrants(grants);"));
        assertTrue(complete.contains("catch (RuntimeException cleanupFailure)"));
        assertTrue(complete.indexOf("fail(id, error)")
                > complete.indexOf("error.addSuppressed(cleanupFailure)"));
        assertTrue(store.contains("for (final String uri : result.returnedUris)"));
        assertFalse(store.contains("getClipData()"));
        assertFalse(store.contains("getExtras()"));
    }
}
