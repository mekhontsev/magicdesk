package io.github.mekhontsev.magicdesk;

import android.content.Intent;

/** Explicit task-instance semantics for one desktop Activity launch. */
enum DesktopTaskInstancePolicy {
    REUSE_EXISTING("reuse"),
    CREATE_NEW("new");

    final String wireName;

    DesktopTaskInstancePolicy(final String wireName) {
        this.wireName = wireName;
    }

    static DesktopTaskInstancePolicy parse(final String value) {
        for (final DesktopTaskInstancePolicy policy : values()) {
            if (policy.wireName.equalsIgnoreCase(value)) {
                return policy;
            }
        }
        throw new IllegalArgumentException("instance must be reuse or new");
    }

    Intent applyTo(final Intent source) {
        if (source == null) {
            throw new IllegalArgumentException("launch Intent is required");
        }
        final Intent intent = new Intent(source);
        intent.removeFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        if (this == CREATE_NEW) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
        return intent;
    }
}
