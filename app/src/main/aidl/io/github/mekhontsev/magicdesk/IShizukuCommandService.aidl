package io.github.mekhontsev.magicdesk;

interface IShizukuCommandService {
    void destroy() = 16777114;

    int uid() = 1;

    String execute(String command) = 2;

    String probeCapabilities() = 3;
}
