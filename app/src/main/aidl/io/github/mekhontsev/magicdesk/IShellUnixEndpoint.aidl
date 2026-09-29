package io.github.mekhontsev.magicdesk;
interface IShellUnixEndpoint {
    oneway void acknowledge(long serial);
    oneway void close();
}
