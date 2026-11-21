package io.github.mekhontsev.magicdesk;
interface IUnixEndpoint {
    String filesystemPath();
    oneway void acknowledge(long serial);
    oneway void close();
}
