package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Parsed standard MimeType list from a Desktop Entry. */
final class DesktopMimeTypes {
    private static final int MAX_TYPES = 128;
    private static final int MAX_TYPE_LENGTH = 255;
    private static final DesktopMimeTypes EMPTY =
            new DesktopMimeTypes(List.of());

    private final List<String> mValues;

    private DesktopMimeTypes(final List<String> values) {
        mValues = Collections.unmodifiableList(new ArrayList<>(values));
    }

    static DesktopMimeTypes empty() {
        return EMPTY;
    }

    static DesktopMimeTypes parse(final String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return EMPTY;
        }
        final Set<String> values = new LinkedHashSet<>();
        for (final String part : encoded.split(";", -1)) {
            if (part.isEmpty()) {
                continue;
            }
            final String value = normalize(part);
            if (!isValid(value)) {
                throw new IllegalArgumentException("invalid MIME type");
            }
            values.add(value);
            if (values.size() > MAX_TYPES) {
                throw new IllegalArgumentException("too many MIME types");
            }
        }
        return values.isEmpty()
                ? EMPTY : new DesktopMimeTypes(new ArrayList<>(values));
    }

    boolean isEmpty() {
        return mValues.isEmpty();
    }

    boolean matches(final String candidate) {
        final String normalized = normalize(candidate);
        if (!isValid(normalized)) {
            return false;
        }
        final int separator = normalized.indexOf('/');
        final String major = normalized.substring(0, separator);
        for (final String declared : mValues) {
            if ("*/*".equals(declared) || declared.equals(normalized)) {
                return true;
            }
            if (!"*".equals(major)
                    && declared.equals(major + "/*")) {
                return true;
            }
        }
        return false;
    }

    String encode() {
        if (mValues.isEmpty()) {
            return "";
        }
        return String.join(";", mValues) + ";";
    }

    List<String> values() {
        return mValues;
    }

    private static String normalize(final String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isValid(final String value) {
        if (value.isEmpty() || value.length() > MAX_TYPE_LENGTH) {
            return false;
        }
        final int separator = value.indexOf('/');
        if (separator <= 0
                || separator != value.lastIndexOf('/')
                || separator == value.length() - 1) {
            return false;
        }
        final String major = value.substring(0, separator);
        final String minor = value.substring(separator + 1);
        if ("*".equals(major)) {
            return "*".equals(minor);
        }
        return validToken(major)
                && ("*".equals(minor) || validToken(minor));
    }

    private static boolean validToken(final String value) {
        if (value.isEmpty() || !alphaNumeric(value.charAt(0))) {
            return false;
        }
        for (int index = 1; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (!alphaNumeric(character)
                    && "!#$&^_.+-".indexOf(character) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean alphaNumeric(final char character) {
        return character >= 'a' && character <= 'z'
                || character >= '0' && character <= '9';
    }
}
