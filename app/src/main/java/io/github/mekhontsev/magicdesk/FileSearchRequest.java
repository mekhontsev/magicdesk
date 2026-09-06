package io.github.mekhontsev.magicdesk;

/** Joins UI cancellation with callbacks and a possibly late Binder start reply. */
final class FileSearchRequest {
    private long mId;
    private boolean mFinished;
    private ShellFileSearchHandle mHandle;

    synchronized boolean isActive() {
        return !mFinished;
    }

    synchronized boolean attach(final ShellFileSearchHandle handle) {
        if (!accepts(handle.id)) {
            return false;
        }
        mHandle = handle;
        return true;
    }

    synchronized boolean accepts(final long id) {
        if (mFinished || id <= 0L || (mId != 0L && mId != id)) {
            return false;
        }
        mId = id;
        return true;
    }

    synchronized boolean finish(final long id) {
        return accepts(id) && complete();
    }

    synchronized boolean complete() {
        if (mFinished) {
            return false;
        }
        mFinished = true;
        mHandle = null;
        return true;
    }

    synchronized ShellFileSearchHandle cancel() {
        final ShellFileSearchHandle handle = mHandle;
        complete();
        return handle;
    }
}
