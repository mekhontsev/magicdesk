package io.github.mekhontsev.magicdesk;

import android.content.ClipDescription;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** MIME declarations, URI evidence, and the resulting content transport metadata. */
final class AndroidContentMimeTypes {
    final List<String> declared;
    final List<String> description;
    final String preferred;

    AndroidContentMimeTypes(
            final List<String> declaredTypes,
            final List<String> itemTypes,
            final boolean hasText,
            final boolean hasHtml) {
        final Set<String> types = new LinkedHashSet<>();
        if (declaredTypes != null) {
            for (final String type : declaredTypes) {
                addType(types, type);
            }
        }
        declared = List.copyOf(types);
        // Derive the Intent type from source evidence, not from metadata that
        // this transport is about to add to its own ClipDescription.
        preferred = selectPreferredMimeType(itemTypes, declared, hasHtml);
        if (hasText) {
            types.add(ClipDescription.MIMETYPE_TEXT_PLAIN);
        }
        if (hasHtml) {
            types.add(ClipDescription.MIMETYPE_TEXT_HTML);
        }
        if (!itemTypes.isEmpty()) {
            types.add(ClipDescription.MIMETYPE_TEXT_URILIST);
            for (final String type : itemTypes) {
                // An unknown item's placeholder must not contradict a uniform
                // declaration when this description is read on the next hop.
                final String normalized = normalize(type);
                if (!"*/*".equals(normalized) || "*/*".equals(preferred)) {
                    types.add(normalized);
                }
            }
        }
        if (types.isEmpty()) {
            types.add(ClipDescription.MIMETYPE_TEXT_PLAIN);
        }
        description = List.copyOf(types);
    }

    List<String> withDeclaration(final String additional) {
        final List<String> result = new ArrayList<>(declared);
        if (additional != null && !additional.trim().isEmpty()) {
            result.add(additional);
        }
        return result;
    }

    private static String selectPreferredMimeType(
            final List<String> itemMimeTypes,
            final List<String> declaredMimeTypes,
            final boolean hasHtml) {
        if (itemMimeTypes.isEmpty()) {
            return hasHtml
                    ? ClipDescription.MIMETYPE_TEXT_HTML
                    : ClipDescription.MIMETYPE_TEXT_PLAIN;
        }
        String selected = selectCommonMimeType(itemMimeTypes, false);
        if (!selected.isEmpty()) {
            return selected;
        }
        selected = selectCommonMimeType(declaredMimeTypes, true);
        return selected.isEmpty() ? "*/*" : selected;
    }

    static String preferredUriMimeType(final List<String> mimeTypes) {
        // ClipDescription lists types for the entire clip, not for each URI.
        final String selected = selectCommonMimeType(mimeTypes, true);
        return selected.isEmpty() ? "*/*" : selected;
    }

    private static String selectCommonMimeType(
            final List<String> mimeTypes,
            final boolean ignoreTransportTypes) {
        String selected = "";
        boolean unknown = false;
        if (mimeTypes == null) {
            return selected;
        }
        for (final String rawMimeType : mimeTypes) {
            final String mimeType = normalize(rawMimeType);
            if ("*/*".equals(mimeType)) {
                unknown = true;
                continue;
            }
            if (ignoreTransportTypes && isTransportMimeType(mimeType)) {
                continue;
            }
            if (selected.isEmpty()) {
                selected = mimeType;
            } else if (!selected.equals(mimeType)) {
                return "*/*";
            }
        }
        // A known type describes the whole selection only if every item agrees.
        // All-unknown items may still use a uniform transport declaration.
        return unknown && !selected.isEmpty() ? "*/*" : selected;
    }

    private static boolean isTransportMimeType(final String mimeType) {
        return ClipDescription.MIMETYPE_TEXT_URILIST.equals(mimeType)
                || ClipDescription.MIMETYPE_TEXT_PLAIN.equals(mimeType)
                || ClipDescription.MIMETYPE_TEXT_HTML.equals(mimeType)
                || ClipDescription.MIMETYPE_TEXT_INTENT.equals(mimeType)
                || FileDragPayload.MIME_TYPE.equals(mimeType);
    }

    private static void addType(final Set<String> target, final String type) {
        if (type != null && !type.trim().isEmpty()) {
            target.add(normalize(type));
        }
    }

    static String normalize(final String mimeType) {
        final String value = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() ? "*/*" : value;
    }
}
