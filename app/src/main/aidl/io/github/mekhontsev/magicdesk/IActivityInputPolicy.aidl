package io.github.mekhontsev.magicdesk;

/** Synchronous registration before a shell-owned Activity creates its window. */
interface IActivityInputPolicy {
    void disableInputSink(IBinder activityToken);
}
