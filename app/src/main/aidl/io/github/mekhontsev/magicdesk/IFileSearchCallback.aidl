package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.ShellFileInfo;

oneway interface IFileSearchCallback {
    void onBatch(long searchId, in ShellFileInfo[] matches);
    void onFinished(
        long searchId, boolean successful, boolean truncated, String message);
}
