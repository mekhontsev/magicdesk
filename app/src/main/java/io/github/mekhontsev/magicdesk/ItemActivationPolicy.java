package io.github.mekhontsev.magicdesk;

final class ItemActivationPolicy {
    private final long mDoubleClickTimeoutMillis;
    private boolean mSingleClick;
    private String mPendingItemId;
    private long mPendingClickTime;

    ItemActivationPolicy(
            final boolean singleClick,
            final long doubleClickTimeoutMillis) {
        mSingleClick = singleClick;
        mDoubleClickTimeoutMillis = Math.max(1L, doubleClickTimeoutMillis);
    }

    void setSingleClick(final boolean singleClick) {
        if (mSingleClick != singleClick) {
            mSingleClick = singleClick;
            reset();
        }
    }

    boolean shouldActivate(final String itemId, final long eventTime) {
        if (mSingleClick) {
            reset();
            return true;
        }
        final boolean activate = itemId != null
                && itemId.equals(mPendingItemId)
                && eventTime >= mPendingClickTime
                && eventTime - mPendingClickTime
                        <= mDoubleClickTimeoutMillis;
        if (activate) {
            reset();
            return true;
        }
        mPendingItemId = itemId;
        mPendingClickTime = eventTime;
        return false;
    }

    void reset() {
        mPendingItemId = null;
        mPendingClickTime = 0L;
    }
}
