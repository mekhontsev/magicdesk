package io.github.mekhontsev.magicdesk;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.Intent;
import android.net.Uri;
import android.os.PersistableBundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Immutable content shared by clipboard, Intent, and drag boundaries. */
final class AndroidContentPayload {
    static final int MAX_URI_ITEMS = 64;

    enum Origin {
        APPLICATION,
        CLIPBOARD,
        DRAG,
        INTENT
    }

    static final class UriItem {
        final Uri uri;
        final String mimeType;

        UriItem(final Uri uri, final String mimeType) {
            if (uri == null) {
                throw new IllegalArgumentException("content URI is required");
            }
            this.uri = uri;
            this.mimeType = normalizeMimeType(mimeType);
        }
    }

    final Origin origin;
    final String label;
    final String subject;
    final String text;
    final String htmlText;
    final List<UriItem> uriItems;
    final List<String> mimeTypes;
    final boolean sensitive;
    final boolean truncated;

    private AndroidContentPayload(
            final Origin origin,
            final String label,
            final String subject,
            final String text,
            final String htmlText,
            final List<UriItem> uriItems,
            final List<String> mimeTypes,
            final boolean sensitive,
            final boolean truncated) {
        this.origin = origin == null ? Origin.APPLICATION : origin;
        this.label = clean(label);
        this.subject = clean(subject);
        this.text = value(text);
        this.htmlText = value(htmlText);
        this.uriItems = immutableUriItems(uriItems);
        this.mimeTypes = immutableMimeTypes(
                mimeTypes, this.text, this.htmlText, this.uriItems);
        this.sensitive = sensitive;
        this.truncated = truncated;
    }

    static AndroidContentPayload text(
            final CharSequence label,
            final CharSequence text,
            final boolean sensitive,
            final Origin origin) {
        if (text == null) {
            throw new IllegalArgumentException("content text is required");
        }
        return new AndroidContentPayload(
                origin,
                label == null ? "" : label.toString(),
                "",
                text.toString(),
                "",
                Collections.emptyList(),
                List.of(ClipDescription.MIMETYPE_TEXT_PLAIN),
                sensitive,
                false);
    }

    static AndroidContentPayload uris(
            final CharSequence label,
            final List<UriItem> items,
            final List<String> additionalMimeTypes,
            final Origin origin) {
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("at least one content URI is required");
        }
        if (items.size() > MAX_URI_ITEMS) {
            throw new IllegalArgumentException(
                    "content URI count exceeds " + MAX_URI_ITEMS);
        }
        return new AndroidContentPayload(
                origin,
                label == null ? "" : label.toString(),
                "",
                "",
                "",
                items,
                additionalMimeTypes,
                false,
                false);
    }

    static AndroidContentPayload drag(
            final CharSequence label,
            final List<Uri> uris,
            final String localMimeType) {
        final List<UriItem> items = new ArrayList<>();
        if (uris != null) {
            for (final Uri uri : uris) {
                if (uri != null) {
                    items.add(new UriItem(uri, "*/*"));
                }
            }
        }
        return new AndroidContentPayload(
                Origin.DRAG,
                label == null ? "" : label.toString(),
                "",
                items.isEmpty() && label != null ? label.toString() : "",
                "",
                items,
                List.of(localMimeType),
                false,
                false);
    }

    static AndroidContentPayload create(
            final Origin origin,
            final CharSequence label,
            final CharSequence subject,
            final CharSequence text,
            final CharSequence htmlText,
            final List<UriItem> uriItems,
            final List<String> mimeTypes,
            final boolean sensitive) {
        return new AndroidContentPayload(
                origin,
                label == null ? "" : label.toString(),
                subject == null ? "" : subject.toString(),
                text == null ? "" : text.toString(),
                htmlText == null ? "" : htmlText.toString(),
                uriItems,
                mimeTypes,
                sensitive,
                false);
    }

    static AndroidContentPayload fromClipData(
            final ClipData clip,
            final Origin origin) {
        if (clip == null || clip.getItemCount() == 0) {
            return empty(origin);
        }
        final ClipDescription description = clip.getDescription();
        final List<String> declaredMimeTypes = mimeTypes(description);
        final String uriMimeType = preferredUriMimeType(declaredMimeTypes);
        final Set<Uri> seenUris = new LinkedHashSet<>();
        final List<UriItem> items = new ArrayList<>();
        String text = "";
        String htmlText = "";
        final int count = Math.min(clip.getItemCount(), MAX_URI_ITEMS);
        for (int index = 0; index < count; index++) {
            final ClipData.Item item = clip.getItemAt(index);
            if (text.isEmpty() && item.getText() != null) {
                text = item.getText().toString();
            }
            if (htmlText.isEmpty() && item.getHtmlText() != null) {
                htmlText = item.getHtmlText();
            }
            final Uri uri = item.getUri();
            if (uri != null && seenUris.add(uri)) {
                items.add(new UriItem(uri, uriMimeType));
            }
        }
        return new AndroidContentPayload(
                origin,
                description == null || description.getLabel() == null
                        ? "" : description.getLabel().toString(),
                "",
                text,
                htmlText,
                items,
                declaredMimeTypes,
                isSensitive(description),
                clip.getItemCount() > count);
    }

    static AndroidContentPayload fromSendIntent(final Intent intent) {
        try {
            return parseSendIntent(intent);
        } catch (final RuntimeException malformedIntent) {
            return empty(Origin.INTENT);
        }
    }

    private static AndroidContentPayload parseSendIntent(final Intent intent) {
        if (intent == null
                || (!Intent.ACTION_SEND.equals(intent.getAction())
                && !Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()))) {
            return empty(Origin.INTENT);
        }
        final AndroidContentPayload clipPayload = fromClipData(
                intent.getClipData(), Origin.INTENT);
        final String sharedText = charSequenceExtra(intent, Intent.EXTRA_TEXT);
        final String sharedHtml = charSequenceExtra(
                intent, Intent.EXTRA_HTML_TEXT);
        final String subject = firstNonEmpty(
                charSequenceExtra(intent, Intent.EXTRA_SUBJECT),
                charSequenceExtra(intent, Intent.EXTRA_TITLE));
        final Set<Uri> seen = new LinkedHashSet<>();
        final List<UriItem> items = new ArrayList<>();
        addUriItems(items, seen, clipPayload.uriItems);
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            final ArrayList<Uri> streams = intent.getParcelableArrayListExtra(
                    Intent.EXTRA_STREAM, Uri.class);
            if (streams != null) {
                for (final Uri uri : streams) {
                    addUri(items, seen, uri, intent.getType());
                }
            }
        } else {
            addUri(
                    items,
                    seen,
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class),
                    intent.getType());
        }
        final int acceptedCount = Math.min(items.size(), MAX_URI_ITEMS);
        return new AndroidContentPayload(
                Origin.INTENT,
                firstNonEmpty(subject, clipPayload.label),
                subject,
                firstNonEmpty(sharedText, clipPayload.text),
                firstNonEmpty(sharedHtml, clipPayload.htmlText),
                items.subList(0, acceptedCount),
                mergeMimeTypes(clipPayload.mimeTypes, intent.getType()),
                clipPayload.sensitive,
                clipPayload.truncated || items.size() > acceptedCount);
    }

    boolean isEmpty() {
        return text.isEmpty() && htmlText.isEmpty() && uriItems.isEmpty();
    }

    boolean hasText() {
        return !text.isEmpty() || !htmlText.isEmpty();
    }

    boolean hasUris() {
        return !uriItems.isEmpty();
    }

    boolean canOpen() {
        return uriItems.size() == 1 || webUriFromText() != null;
    }

    boolean canShare() {
        return !isEmpty();
    }

    Uri openUri() {
        if (uriItems.size() == 1) {
            return uriItems.get(0).uri;
        }
        return webUriFromText();
    }

    List<Uri> uris() {
        final List<Uri> result = new ArrayList<>(uriItems.size());
        for (final UriItem item : uriItems) {
            result.add(item.uri);
        }
        return Collections.unmodifiableList(result);
    }

    String preferredMimeType() {
        final List<String> itemMimeTypes = new ArrayList<>(uriItems.size());
        for (final UriItem item : uriItems) {
            itemMimeTypes.add(item.mimeType);
        }
        return selectPreferredMimeType(
                itemMimeTypes,
                mimeTypes,
                !uriItems.isEmpty(),
                !htmlText.isEmpty());
    }

    static String selectPreferredMimeType(
            final List<String> itemMimeTypes,
            final List<String> declaredMimeTypes,
            final boolean hasUris,
            final boolean hasHtml) {
        if (!hasUris) {
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

    private static String selectCommonMimeType(
            final List<String> mimeTypes,
            final boolean ignoreTransportTypes) {
        String selected = "";
        if (mimeTypes == null) {
            return selected;
        }
        for (final String rawMimeType : mimeTypes) {
            final String mimeType = normalizeMimeType(rawMimeType);
            if ("*/*".equals(mimeType)) {
                continue;
            }
            if (ignoreTransportTypes && isTransportMimeType(mimeType)) {
                continue;
            }
            if (selected.isEmpty()) {
                selected = mimeType;
            } else if (!selected.equalsIgnoreCase(mimeType)) {
                return "*/*";
            }
        }
        return selected;
    }

    ClipData toClipData() {
        if (isEmpty()) {
            throw new IllegalStateException("empty content cannot become ClipData");
        }
        final ClipDescription description = new ClipDescription(
                label.isEmpty() ? "MagicDesk content" : label,
                mimeTypes.toArray(new String[0]));
        if (sensitive) {
            final PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            description.setExtras(extras);
        }
        final CharSequence clipText = text.isEmpty() ? null : text;
        final String clipHtml = htmlText.isEmpty() ? null : htmlText;
        final Uri firstUri = uriItems.isEmpty() ? null : uriItems.get(0).uri;
        final ClipData clip = new ClipData(
                description,
                new ClipData.Item(clipText, clipHtml, null, firstUri));
        for (int index = 1; index < uriItems.size(); index++) {
            clip.addItem(new ClipData.Item(uriItems.get(index).uri));
        }
        return clip;
    }

    private Uri webUriFromText() {
        final String url = WebShortcutShareRequest.findHttpUrl(text);
        return url == null ? null : Uri.parse(url);
    }

    private static AndroidContentPayload empty(final Origin origin) {
        return new AndroidContentPayload(
                origin,
                "",
                "",
                "",
                "",
                Collections.emptyList(),
                Collections.emptyList(),
                false,
                false);
    }

    private static List<UriItem> immutableUriItems(
            final List<UriItem> items) {
        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }
        if (items.size() > MAX_URI_ITEMS) {
            throw new IllegalArgumentException(
                    "content URI count exceeds " + MAX_URI_ITEMS);
        }
        final List<UriItem> copy = new ArrayList<>(items.size());
        final Set<Uri> seen = new LinkedHashSet<>();
        for (final UriItem item : items) {
            if (item != null && seen.add(item.uri)) {
                copy.add(item);
            }
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<String> immutableMimeTypes(
            final List<String> declared,
            final String text,
            final String htmlText,
            final List<UriItem> items) {
        final Set<String> result = new LinkedHashSet<>();
        if (declared != null) {
            for (final String mimeType : declared) {
                addMimeType(result, mimeType);
            }
        }
        if (!text.isEmpty()) {
            result.add(ClipDescription.MIMETYPE_TEXT_PLAIN);
        }
        if (!htmlText.isEmpty()) {
            result.add(ClipDescription.MIMETYPE_TEXT_HTML);
        }
        if (!items.isEmpty()) {
            result.add(ClipDescription.MIMETYPE_TEXT_URILIST);
            for (final UriItem item : items) {
                addMimeType(result, item.mimeType);
            }
        }
        if (result.isEmpty()) {
            result.add(ClipDescription.MIMETYPE_TEXT_PLAIN);
        }
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    private static List<String> mimeTypes(
            final ClipDescription description) {
        if (description == null) {
            return Collections.emptyList();
        }
        final List<String> result = new ArrayList<>();
        for (int index = 0; index < description.getMimeTypeCount(); index++) {
            result.add(description.getMimeType(index));
        }
        return result;
    }

    private static List<String> mergeMimeTypes(
            final List<String> existing,
            final String additional) {
        final List<String> result = new ArrayList<>();
        if (existing != null) {
            result.addAll(existing);
        }
        if (additional != null && !additional.trim().isEmpty()) {
            result.add(additional);
        }
        return result;
    }

    private static String preferredUriMimeType(
            final List<String> mimeTypes) {
        if (mimeTypes != null) {
            for (final String mimeType : mimeTypes) {
                if (!isTransportMimeType(mimeType)) {
                    return normalizeMimeType(mimeType);
                }
            }
        }
        return "*/*";
    }

    private static boolean isTransportMimeType(final String mimeType) {
        return ClipDescription.MIMETYPE_TEXT_URILIST.equals(mimeType)
                || ClipDescription.MIMETYPE_TEXT_PLAIN.equals(mimeType)
                || ClipDescription.MIMETYPE_TEXT_HTML.equals(mimeType)
                || ClipDescription.MIMETYPE_TEXT_INTENT.equals(mimeType)
                || FileDragPayload.MIME_TYPE.equals(mimeType);
    }

    private static boolean isSensitive(
            final ClipDescription description) {
        final PersistableBundle extras = description == null
                ? null : description.getExtras();
        return extras != null
                && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false);
    }

    private static void addUriItems(
            final List<UriItem> target,
            final Set<Uri> seen,
            final List<UriItem> source) {
        for (final UriItem item : source) {
            addUri(target, seen, item.uri, item.mimeType);
        }
    }

    private static void addUri(
            final List<UriItem> target,
            final Set<Uri> seen,
            final Uri uri,
            final String mimeType) {
        if (uri != null && seen.add(uri)) {
            target.add(new UriItem(uri, mimeType));
        }
    }

    private static void addMimeType(
            final Set<String> target,
            final String mimeType) {
        if (mimeType != null && !mimeType.trim().isEmpty()) {
            target.add(normalizeMimeType(mimeType));
        }
    }

    private static String normalizeMimeType(final String mimeType) {
        final String value = clean(mimeType).toLowerCase(Locale.ROOT);
        return value.isEmpty() ? "*/*" : value;
    }

    private static String charSequenceExtra(
            final Intent intent,
            final String name) {
        final CharSequence value = intent.getCharSequenceExtra(name);
        return value == null ? "" : value.toString();
    }

    private static String firstNonEmpty(
            final String first,
            final String second) {
        return first == null || first.isEmpty() ? value(second) : first;
    }

    private static String clean(final CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String value(final String value) {
        return value == null ? "" : value;
    }
}
