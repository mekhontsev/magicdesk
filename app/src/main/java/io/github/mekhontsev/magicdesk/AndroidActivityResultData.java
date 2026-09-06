package io.github.mekhontsev.magicdesk;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.function.IntFunction;

/** Bounded result projection; URI grants use the same validated addresses as consumers. */
final class AndroidActivityResultData {
    static final int MAX_CLIP_ITEMS = 32;
    static final int MAX_EXTRAS = 32;
    static final int MAX_TEXT_CHARS = 8_192;

    final JSONObject json;
    final int grantFlags;
    final List<String> returnedUris;

    private AndroidActivityResultData(
            final JSONObject json, final int grantFlags, final List<String> returnedUris) {
        this.json = json;
        this.grantFlags = grantFlags;
        this.returnedUris = List.copyOf(returnedUris);
    }

    static JSONObject describeIntent(final Intent intent) throws JSONException {
        return intent == null ? new JSONObject() : describeIdentity(
                intent.getAction(), intent.getDataString(), intent.getType(),
                intent.getComponent() == null ? null
                        : intent.getComponent().flattenToShortString(),
                intent.getPackage());
    }

    static JSONObject describeIdentity(
            final String action, final String uri, final String mimeType,
            final String component, final String packageName) throws JSONException {
        return new JSONObject()
                .put("action", identity(action, "action"))
                .put("dataUri", identity(uri, "data URI"))
                .put("mimeType", identity(mimeType, "MIME type"))
                .put("component", identity(component, "component"))
                .put("package", identity(packageName, "package"));
    }

    static AndroidActivityResultData read(final Intent intent) throws JSONException {
        final JSONObject data = describeIntent(intent);
        if (intent == null) {
            return new AndroidActivityResultData(data, 0, List.of());
        }
        final ClipData clip = intent.getClipData();
        final int clipCount = clip == null ? 0 : clip.getItemCount();
        final List<String> clipUris = readClipUris(clipCount, index -> {
            final Uri uri = clip.getItemAt(index).getUri();
            return uri == null ? null : uri.toString();
        });
        final int flags = intent.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        data.put("clipUris", new JSONArray(clipUris))
                .put("clipUrisTruncated", clipCount > MAX_CLIP_ITEMS)
                .put("grantFlags", flags);
        try {
            final Bundle extras = intent.getExtras();
            appendExtras(data, extras == null ? Collections.emptyIterator()
                    : extras.keySet().iterator(), name -> extras.get(name));
        } catch (RuntimeException ignored) {
            // A malformed optional Bundle must not lose the selected document URIs.
            data.put("extras", new JSONObject()).put("extrasTruncated", true);
        }
        final LinkedHashSet<String> uris = new LinkedHashSet<>();
        final String primary = data.getString("dataUri");
        if (!primary.isEmpty()) {
            uris.add(primary);
        }
        uris.addAll(clipUris);
        return new AndroidActivityResultData(data, flags, new ArrayList<>(uris));
    }

    static List<String> readClipUris(final int count, final IntFunction<String> readUri) {
        final List<String> uris = new ArrayList<>();
        for (int index = 0; index < Math.min(count, MAX_CLIP_ITEMS); index++) {
            final String uri = readUri.apply(index);
            if (uri != null) {
                uris.add(identity(uri, "ClipData URI"));
            }
        }
        return uris;
    }

    static void appendExtras(
            final JSONObject data, final Iterator<String> names,
            final Function<String, Object> readExtra) throws JSONException {
        final JSONObject extras = new JSONObject();
        boolean truncated = false;
        int inspected = 0;
        while (inspected < MAX_EXTRAS && names.hasNext()) {
            final String name = names.next();
            inspected++;
            if (name == null || name.length() > MAX_TEXT_CHARS) {
                truncated = true;
                continue;
            }
            try {
                final Object item = readExtra.apply(name);
                final Object value;
                if (item == null) {
                    value = JSONObject.NULL;
                } else if (item instanceof CharSequence) {
                    final CharSequence text = (CharSequence) item;
                    value = BoundedText.prefix(text, MAX_TEXT_CHARS);
                    truncated |= text.length() > MAX_TEXT_CHARS;
                } else if (item instanceof Uri) {
                    value = identity(item.toString(), "extra URI");
                } else if (item instanceof Boolean || item instanceof Byte
                        || item instanceof Short || item instanceof Integer
                        || item instanceof Long
                        || (item instanceof Float && Float.isFinite((Float) item))
                        || (item instanceof Double && Double.isFinite((Double) item))) {
                    value = item;
                } else {
                    truncated = true;
                    continue;
                }
                extras.put(name, value);
            } catch (RuntimeException ignored) {
                truncated = true;
            }
        }
        data.put("extras", extras)
                .put("extrasTruncated", truncated || names.hasNext());
    }

    private static String identity(final String value, final String field) {
        if (value == null) {
            return "";
        }
        if (value.length() > MAX_TEXT_CHARS) {
            // Prefixes are meaningful for text, never for addresses or identifiers.
            throw new IllegalArgumentException("Activity result " + field
                    + " exceeds " + MAX_TEXT_CHARS + " characters");
        }
        return value;
    }
}
