package io.github.mekhontsev.magicdesk;

/** Optional transient firmware working-state hints. Power and lifetime are shared policy. */
public interface PlatformBackgroundWork {
    PlatformBackgroundWork NONE = uid -> () -> { };

    Session begin(int uid) throws Exception;
    default long refreshIntervalMillis() { return 0; }

    interface Session extends AutoCloseable {
        default void refresh() throws Exception { }
        @Override void close();
    }
}
