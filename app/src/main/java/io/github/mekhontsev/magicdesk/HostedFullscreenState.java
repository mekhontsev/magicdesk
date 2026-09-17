package io.github.mekhontsev.magicdesk;

/** Host acknowledgement follows Android mode, not merely submission of an insets request. */
final class HostedFullscreenState {
    private boolean requested, pending, entered, restoreWindowed;

    boolean requested() { return requested; }

    void request(boolean value, boolean multiWindow) {
        if (value && !requested) restoreWindowed = multiWindow;
        requested = value;
        pending = true;
    }

    Boolean observe(boolean multiWindow) {
        if (pending) {
            if (requested && !multiWindow) {
                pending = false;
                entered = true;
                return true;
            }
            if (!requested && (!restoreWindowed || multiWindow)) {
                pending = entered = restoreWindowed = false;
                return false;
            }
        } else if (entered && multiWindow) {
            // A user restore/snap overrides the application's previous request.
            requested = entered = restoreWindowed = false;
            return false;
        }
        return null;
    }

    void reject() { requested = pending = entered = restoreWindowed = false; }
}
