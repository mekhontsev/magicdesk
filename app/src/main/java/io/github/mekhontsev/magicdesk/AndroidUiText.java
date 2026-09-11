package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

/** Pages are UTF-16 offsets into one snapshot, never a mixture of live editor revisions. */
final class AndroidUiText {
    static JSONObject page(final CharSequence text, final boolean redacted, final JSONObject args)
            throws JSONException {
        final int offset = AndroidUiSelector.integer(args, "offset", 0, 0, Integer.MAX_VALUE);
        final int limit = AndroidUiSelector.integer(args, "limit", 32768, 1, 32768);
        if (redacted) {
            return new JSONObject().put("redacted", true).put("text", JSONObject.NULL)
                    .put("totalLength", JSONObject.NULL).put("nextOffset", JSONObject.NULL);
        }
        final CharSequence value = text == null ? "" : text;
        if (offset > value.length()) throw new IllegalArgumentException("offset exceeds snapshot text length");
        if (splitsPair(value, offset)) throw new IllegalArgumentException("offset splits a Unicode surrogate pair");
        int end = offset + Math.min(limit, value.length() - offset);
        // A JSON/UTF-8 response cannot transport half a supplementary character.
        if (splitsPair(value, end)) end += end - offset == 1 ? 1 : -1;
        return new JSONObject().put("redacted", false).put("text", value.subSequence(offset, end).toString())
                .put("offset", offset).put("totalLength", value.length())
                .put("nextOffset", end < value.length() ? end : JSONObject.NULL);
    }

    private static boolean splitsPair(final CharSequence value, final int offset) {
        return offset > 0 && offset < value.length() && Character.isHighSurrogate(value.charAt(offset - 1))
                && Character.isLowSurrogate(value.charAt(offset));
    }
}
