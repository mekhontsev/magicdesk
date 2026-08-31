package io.github.mekhontsev.magicdesk;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;

/** Converts shared Android content to explicit user-facing Intent contracts. */
final class AndroidContentIntentAdapter {
    private AndroidContentIntentAdapter() {
    }

    static Intent open(final AndroidContentPayload payload) {
        if (payload == null || !payload.canOpen()) {
            return null;
        }
        final Uri uri = payload.openUri();
        final Intent intent = new Intent(Intent.ACTION_VIEW);
        if (payload.hasUris()) {
            intent.setDataAndType(uri, payload.preferredMimeType());
            intent.setClipData(payload.toClipData());
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.setData(uri).addCategory(Intent.CATEGORY_BROWSABLE);
        }
        return intent;
    }

    static Intent share(final AndroidContentPayload payload) {
        if (payload == null || !payload.canShare()) {
            return null;
        }
        final ArrayList<Uri> uris = new ArrayList<>(payload.uris());
        final Intent intent = new Intent(
                uris.size() > 1
                        ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND)
                .setType(payload.preferredMimeType());
        if (!payload.text.isEmpty()) {
            intent.putExtra(Intent.EXTRA_TEXT, payload.text);
        }
        if (!payload.htmlText.isEmpty()) {
            intent.putExtra(Intent.EXTRA_HTML_TEXT, payload.htmlText);
        }
        if (!payload.subject.isEmpty()) {
            intent.putExtra(Intent.EXTRA_SUBJECT, payload.subject);
        }
        if (uris.size() == 1) {
            intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else if (!uris.isEmpty()) {
            intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        final ClipData clip = payload.toClipData();
        intent.setClipData(clip);
        if (!uris.isEmpty()) {
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        return intent;
    }
}
