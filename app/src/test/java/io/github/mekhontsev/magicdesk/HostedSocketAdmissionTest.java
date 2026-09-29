package io.github.mekhontsev.magicdesk;

import java.nio.file.Path;
import org.junit.Test;

public final class HostedSocketAdmissionTest {
    @Test public void transferOwnsDescriptorsAcrossQueueFailureAndShutdown() throws Exception {
        String source = Path.of("../hosted-runtime/src/main/java/io/github/mekhontsev/magicdesk/hosted/HostedSocketAdmission.java")
                .toAbsolutePath().toString();
        RuntimeSourceFixture.verify(RuntimeSourceFixture.methods(source, "offer", "close", "discard") + """
            static class ParcelFileDescriptor implements Closeable {
                final int fd;
                boolean detached;
                int closes;
                ParcelFileDescriptor(int fd) { this.fd = fd; }
                int detachFd() { check(closes == 0 && !detached, "invalid transfer"); detached = true; return fd; }
                public void close() throws IOException { closes++; }
            }
            static class Handler {
                final ArrayDeque<Runnable> queue = new ArrayDeque<>();
                boolean accepts = true;
                boolean post(Runnable work) { if (!accepts) return false; queue.add(work); return true; }
                void drain() { while (!queue.isEmpty()) queue.remove().run(); }
            }
            final Handler handler = new Handler();
            final HashSet<ParcelFileDescriptor> pending = new HashSet<>();
            boolean closed, available = true;
            final java.util.function.BooleanSupplier ready = () -> available;
            final List<Integer> received = new ArrayList<>();
            final java.util.function.IntConsumer accept = received::add;
            public static void verify() throws Exception {
                var active = new Fixture();
                var first = new ParcelFileDescriptor(1);
                active.offer(first);
                check(!first.detached && first.closes == 0, "Binder caller transferred before protocol loop");
                active.handler.drain(); active.close();
                check(first.detached && first.closes == 1 && active.received.equals(List.of(1)), "single transfer");

                var stopped = new Fixture();
                var second = new ParcelFileDescriptor(2);
                stopped.offer(second); stopped.available = false; stopped.handler.drain();
                check(!second.detached && second.closes == 1 && stopped.received.isEmpty(), "not-ready FD leaked");

                var queued = new Fixture();
                var third = new ParcelFileDescriptor(3);
                queued.offer(third); queued.close(); queued.close(); queued.handler.drain();
                check(!third.detached && third.closes == 1 && queued.received.isEmpty(), "close raced queued admission");
                var late = new ParcelFileDescriptor(4); queued.offer(late);
                check(late.closes == 1 && queued.handler.queue.isEmpty(), "late FD retained");

                var rejected = new Fixture(); rejected.handler.accepts = false;
                var fourth = new ParcelFileDescriptor(5); rejected.offer(fourth); rejected.close();
                check(fourth.closes == 1 && !fourth.detached && rejected.pending.isEmpty(), "post rejection leaked FD");

                var bounded = new Fixture();
                for (int i = 0; i < 32; i++) bounded.offer(new ParcelFileDescriptor(i));
                var excess = new ParcelFileDescriptor(99); bounded.offer(excess);
                check(excess.closes == 1 && bounded.pending.size() == 32, "unbounded admission queue");
                var retained = List.copyOf(bounded.pending);
                bounded.close(); bounded.handler.drain();
                check(retained.stream().allMatch(fd -> fd.closes == 1 && !fd.detached)
                        && bounded.received.isEmpty(), "shutdown leaked queued FDs");
            }
            """);
    }
}
