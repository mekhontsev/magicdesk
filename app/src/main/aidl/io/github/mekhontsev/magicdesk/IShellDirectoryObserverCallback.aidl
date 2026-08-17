package io.github.mekhontsev.magicdesk;

oneway interface IShellDirectoryObserverCallback {
    void onDirectoryChanged(String absolutePath);
}
