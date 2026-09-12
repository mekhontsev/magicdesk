package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.Map;

/** One explicit input destination; preparation belongs to independent workspace residencies. */
final class DisplayInputTarget {
    private final Map<Integer, String> mDesktops = new LinkedHashMap<>();
    private final Map<Integer, String> mPrepared = new LinkedHashMap<>();
    private int mPendingDesktop = -1;
    private int mSelected = -1;

    void reconcile(final Map<Integer, String> workspaces) {
        if (mDesktops.containsKey(mSelected) && !workspaces.containsKey(mSelected)) { mSelected = -1; }
        if (!workspaces.containsKey(mPendingDesktop)) { mPendingDesktop = -1; }
        for (final var entry : workspaces.entrySet()) {
            if (!entry.getValue().equals(mDesktops.get(entry.getKey()))) {
                if (mSelected == entry.getKey()) { mSelected = -1; }
                mPendingDesktop = entry.getKey();
                mPrepared.remove(entry.getKey());
            }
        }
        mPrepared.keySet().retainAll(workspaces.keySet());
        mDesktops.clear();
        mDesktops.putAll(workspaces);
    }

    void prepared(final int displayId, final String workspaceId) {
        if (!workspaceId.equals(mDesktops.get(displayId))) { return; }
        mPrepared.put(displayId, workspaceId);
        if (mPendingDesktop == displayId) {
            mPendingDesktop = -1;
            mSelected = displayId;
        }
    }

    void select(final int displayId) {
        if (mDesktops.containsKey(displayId) && !mPrepared.containsKey(displayId)) {
            throw new IllegalStateException("desktop input is not prepared");
        }
        mPendingDesktop = -1;
        mSelected = displayId;
    }

    int requestedDisplay() { return mSelected; }
    int readyTarget() { return mSelected; }
    boolean desktopShortcuts() { return mPrepared.containsKey(mSelected); }

    boolean release(final int displayId) {
        if (mPendingDesktop == displayId) { mPendingDesktop = -1; }
        final boolean selected = mSelected == displayId;
        if (selected) { mSelected = -1; }
        return selected;
    }
}
