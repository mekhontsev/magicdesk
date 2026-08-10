package io.github.mekhontsev.magicdesk;

oneway interface IDesktopFolderObserverCallback {
    void onDesktopFolderChanged(String relativePath);
}
