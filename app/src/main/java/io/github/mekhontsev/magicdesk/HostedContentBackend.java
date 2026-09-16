package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.List;

/** Content boundary for an Android host. No guest window IDs or protocol target names cross it. */
interface HostedContentBackend extends AutoCloseable {
    @FunctionalInterface
    interface Content {
        AndroidContentPayload read() throws IOException;
    }

    interface DragOffer extends Content {
        void begin();
        void finish(boolean accepted);
    }

    interface Drop extends AutoCloseable {
        void enter();
        void move(float x, float y);
        void leave();
        void drop();
        @Override void close();
    }

    interface Listener {
        void clipboardOffered(Content content);
        void dragOffered(DragOffer offer);
        void dropFinished(Drop drop, boolean accepted);
        void dragCancelled();
    }

    String clipboardIdentity();
    void listen(Listener listener);
    void focus(boolean focused);
    void publishClipboard(AndroidContentPayload payload);

    /** Null rejects unsupported MIME types. Content becomes readable only after Android grants the drop. */
    Drop createDrop(List<String> mimeTypes, AndroidContentPayload localContent, Content content, DragOffer localOffer);
    @Override void close();
}
