package io.github.mekhontsev.magicdesk;

oneway interface IFileOperationCallback {
    void onProgress(
        long operationId,
        int completedItems,
        int totalItems,
        String currentPath,
        long bytesCompleted);

    void onFinished(long operationId, boolean successful, String message);
}
