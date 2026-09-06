package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;
import org.junit.Test;

public final class AndroidActivityResultDataTest {
    private static final int LIMIT = AndroidActivityResultData.MAX_TEXT_CHARS;

    @Test
    public void identityPreservesUriEscapingAndWhitespace() throws Exception {
        final String uri = "content://provider/a%2Fb?token=x%2By+z#part";
        final JSONObject data = AndroidActivityResultData.describeIdentity(
                " action ", uri, "text/plain", "sample/.Picker", "sample");
        assertEquals(" action ", data.getString("action"));
        assertEquals(uri, data.getString("dataUri"));
        assertEquals("text/plain", data.getString("mimeType"));
        assertEquals("sample/.Picker", data.getString("component"));
        assertEquals("sample", data.getString("package"));
    }

    @Test
    public void missingIdentityFieldsAreEmptyStrings() throws Exception {
        final JSONObject data = AndroidActivityResultData.describeIdentity(
                null, null, null, null, null);
        for (final String field : List.of("action", "dataUri", "mimeType", "component", "package")) {
            assertEquals("", data.getString(field));
        }
    }

    @Test
    public void oversizedIdentityFailsInsteadOfChangingAnAddressOrIdentifier() {
        for (int index = 0; index < 5; index++) {
            final String[] values = {"action", "content://provider/document", "text/plain",
                    "sample/.Picker", "sample"};
            values[index] = "x".repeat(LIMIT + 1);
            final IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> AndroidActivityResultData.describeIdentity(
                            values[0], values[1], values[2], values[3], values[4]));
            assertTrue(failure.getMessage().contains("exceeds"));
            assertFalse(failure.getMessage().contains(values[index]));
        }
    }

    @Test
    public void exactIdentityLimitIsPreserved() throws Exception {
        final String prefix = "content://provider/";
        final String uri = prefix + "a".repeat(LIMIT - prefix.length() - 2) + "\ud83d\ude80";
        assertEquals(LIMIT, uri.length());
        assertEquals(uri, AndroidActivityResultData.describeIdentity(
                null, uri, null, null, null).getString("dataUri"));
    }

    @Test
    public void clipLimitCountsInspectedItemsIncludingNonUriItems() {
        final AtomicInteger reads = new AtomicInteger();
        final List<String> uris = AndroidActivityResultData.readClipUris(100, index -> {
            reads.incrementAndGet();
            return index == 31 ? "content://provider/document" : null;
        });
        assertEquals(AndroidActivityResultData.MAX_CLIP_ITEMS, reads.get());
        assertEquals(List.of("content://provider/document"), uris);
    }

    @Test
    public void clipProjectionPreservesOrderDuplicatesAndFullAddresses() {
        final List<String> source = List.of("content://p/a%2Fb", "content://p/a%2Fb", "https://p/c");
        assertEquals(source, AndroidActivityResultData.readClipUris(source.size(), source::get));
    }

    @Test
    public void oversizedClipUriRejectsTheResult() {
        assertThrows(IllegalArgumentException.class,
                () -> AndroidActivityResultData.readClipUris(1, index -> "x".repeat(LIMIT + 1)));
    }

    @Test
    public void absentClipAndResultNeedNoReads() throws Exception {
        assertTrue(AndroidActivityResultData.readClipUris(0, index -> {
            throw new AssertionError("no clip item exists");
        }).isEmpty());
        final AndroidActivityResultData result = AndroidActivityResultData.read(null);
        assertEquals(0, result.json.length());
        assertEquals(0, result.grantFlags);
        assertTrue(result.returnedUris.isEmpty());
    }

    @Test
    public void ordinaryScalarsAndNullArePreserved() throws Exception {
        final Map<String, Object> source = new LinkedHashMap<>();
        source.put("text", " \tname\n");
        source.put("bool", true);
        source.put("byte", (byte) 1);
        source.put("short", (short) 2);
        source.put("int", 3);
        source.put("long", Long.MAX_VALUE);
        source.put("float", 1.5f);
        source.put("double", -2.75d);
        source.put("null", null);
        final JSONObject data = extras(source);
        final JSONObject projected = data.getJSONObject("extras");
        assertEquals(source.size(), projected.length());
        for (final Map.Entry<String, Object> entry : source.entrySet()) {
            assertEquals(entry.getValue() == null ? JSONObject.NULL : entry.getValue(),
                    projected.get(entry.getKey()));
        }
        assertFalse(data.getBoolean("extrasTruncated"));
    }

    @Test
    public void invalidNumbersDoNotDiscardLaterExtras() throws Exception {
        final Map<String, Object> source = new LinkedHashMap<>();
        source.put("nan", Double.NaN);
        source.put("positiveInfinity", Double.POSITIVE_INFINITY);
        source.put("negativeInfinity", Float.NEGATIVE_INFINITY);
        source.put("customNumber", new AtomicInteger(1));
        source.put("valid", 42);
        final JSONObject data = extras(source);
        assertEquals(1, data.getJSONObject("extras").length());
        assertEquals(42, data.getJSONObject("extras").getInt("valid"));
        assertTrue(data.getBoolean("extrasTruncated"));
    }

    @Test
    public void unreadableExtraDoesNotDiscardLaterExtras() throws Exception {
        final JSONObject data = new JSONObject();
        AndroidActivityResultData.appendExtras(data, List.of("bad", "good").iterator(), name -> {
            if (name.equals("bad")) {
                throw new IllegalStateException("unreadable Parcelable");
            }
            return "value";
        });
        assertEquals(1, data.getJSONObject("extras").length());
        assertEquals("value", data.getJSONObject("extras").getString("good"));
        assertTrue(data.getBoolean("extrasTruncated"));
    }

    @Test
    public void unsupportedAndUnreadableExtrasConsumeTheInspectionBudget() throws Exception {
        final List<String> keys = new ArrayList<>();
        for (int index = 0; index < 100; index++) {
            keys.add(Integer.toString(index));
        }
        final AtomicInteger reads = new AtomicInteger();
        final JSONObject data = new JSONObject();
        AndroidActivityResultData.appendExtras(data, keys.iterator(), name -> {
            if (reads.incrementAndGet() % 2 == 0) {
                throw new IllegalArgumentException("bad extra");
            }
            return new Object();
        });
        assertEquals(AndroidActivityResultData.MAX_EXTRAS, reads.get());
        assertEquals(0, data.getJSONObject("extras").length());
        assertTrue(data.getBoolean("extrasTruncated"));
    }

    @Test
    public void oversizedKeyCannotCollideWithItsPrefixOrReadItsValue() throws Exception {
        final String key = "k".repeat(LIMIT);
        final JSONObject data = new JSONObject();
        AndroidActivityResultData.appendExtras(data, List.of(key, key + "x").iterator(), name -> {
            assertEquals(key, name);
            return "original";
        });
        assertEquals(1, data.getJSONObject("extras").length());
        assertEquals("original", data.getJSONObject("extras").getString(key));
        assertTrue(data.getBoolean("extrasTruncated"));
    }

    @Test
    public void oversizedKeysAlsoConsumeTheInspectionBudget() throws Exception {
        final List<String> keys = new ArrayList<>(Collections.nCopies(
                AndroidActivityResultData.MAX_EXTRAS, "x".repeat(LIMIT + 1)));
        keys.add("good");
        final JSONObject data = new JSONObject();
        AndroidActivityResultData.appendExtras(data, keys.iterator(), name -> {
            throw new AssertionError("no value should be read");
        });
        assertEquals(0, data.getJSONObject("extras").length());
        assertTrue(data.getBoolean("extrasTruncated"));
    }

    @Test
    public void textTruncationDoesNotSplitASurrogatePair() throws Exception {
        final String prefix = "a".repeat(LIMIT - 1);
        final JSONObject data = extras(Map.of("text", prefix + "\ud83d\ude80"));
        assertEquals(prefix, data.getJSONObject("extras").getString("text"));
        assertTrue(data.getBoolean("extrasTruncated"));
    }

    @Test
    public void charSequenceIsBoundedBeforeMaterializing() throws Exception {
        final CharSequence text = new CharSequence() {
            @Override public int length() { return LIMIT + 1; }
            @Override public char charAt(final int index) { return 'x'; }
            @Override public CharSequence subSequence(final int start, final int end) {
                assertEquals(0, start);
                assertEquals(LIMIT, end);
                return "x".repeat(end);
            }
            @Override public String toString() {
                throw new AssertionError("must not materialize the full CharSequence");
            }
        };
        final JSONObject data = extras(Map.of("text", text));
        assertEquals("x".repeat(LIMIT), data.getJSONObject("extras").getString("text"));
        assertTrue(data.getBoolean("extrasTruncated"));
    }

    @Test
    public void exactExtrasAndTextLimitsAreNotTruncation() throws Exception {
        final Map<String, Object> source = new LinkedHashMap<>();
        for (int index = 0; index < AndroidActivityResultData.MAX_EXTRAS; index++) {
            source.put("key" + index, "x".repeat(LIMIT - 2) + "\ud83d\ude80");
        }
        assertFalse(extras(source).getBoolean("extrasTruncated"));
        source.put("overflow", "value");
        final JSONObject data = extras(source);
        assertEquals(AndroidActivityResultData.MAX_EXTRAS, data.getJSONObject("extras").length());
        assertFalse(data.getJSONObject("extras").has("overflow"));
        assertTrue(data.getBoolean("extrasTruncated"));
    }

    @Test
    public void emptyExtrasAreComplete() throws Exception {
        final JSONObject data = extras(Map.of());
        assertEquals(0, data.getJSONObject("extras").length());
        assertFalse(data.getBoolean("extrasTruncated"));
    }

    private static JSONObject extras(final Map<String, ?> source) throws Exception {
        final JSONObject data = new JSONObject();
        AndroidActivityResultData.appendExtras(data, source.keySet().iterator(), source::get);
        return data;
    }
}
