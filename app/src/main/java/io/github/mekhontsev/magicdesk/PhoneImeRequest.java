package io.github.mekhontsev.magicdesk;

/** One explicit keyboard request, independent of its text-input transport. */
final class PhoneImeRequest {
    private boolean mRequested;
    private boolean mShowIssued;
    private boolean mWasVisible;
    private long mNextConnection;
    private long mConnection;

    void begin() {
        mRequested = true;
    }

    boolean isRequested() {
        return mRequested;
    }

    long openConnection() {
        mConnection = mRequested ? ++mNextConnection : 0;
        return mConnection;
    }

    long currentConnection() {
        return mConnection;
    }

    boolean accepts(final long connection) {
        return mRequested && connection != 0 && connection == mConnection;
    }

    void closeConnection(final long connection) {
        if (accepts(connection)) {
            mConnection = 0;
        }
    }

    boolean takeShowRequest(final boolean windowFocused) {
        if (!mRequested || mShowIssued || mConnection == 0 || !windowFocused) {
            return false;
        }
        mShowIssued = true;
        return true;
    }

    boolean wasDismissed(final boolean visible) {
        if (!mRequested || !mShowIssued) {
            return false;
        }
        // An initial hidden inset is not a dismissal of a pending show.
        if (visible) {
            mWasVisible = true;
        }
        return mWasVisible && !visible;
    }

    void cancel() {
        mRequested = false;
        mShowIssued = false;
        mWasVisible = false;
        mConnection = 0;
    }
}
