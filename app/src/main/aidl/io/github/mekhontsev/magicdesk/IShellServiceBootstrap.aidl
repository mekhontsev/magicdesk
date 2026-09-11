package io.github.mekhontsev.magicdesk;

interface IShellServiceBootstrap {
    int start(String service, String token, int userId) = 1;
    void destroy() = 16777114;
}
