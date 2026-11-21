package io.github.mekhontsev.magicdesk;
import io.github.mekhontsev.magicdesk.IUnixEndpoint;
import io.github.mekhontsev.magicdesk.IUnixConnectionReceiver;
interface IExecutorUnixEndpoints {
    IUnixEndpoint open(String name, int uid, int timeout, IUnixConnectionReceiver receiver);
    void retain(IBinder owner);
    void close();
}
