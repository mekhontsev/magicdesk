package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class OperationResourcesTest {
    @Test public void completionBeforeAttachAndRepeatedCloseReleaseOnce() {
        var resources = new OperationResources();
        var slot = resources.reserve();
        var calls = new AtomicInteger();
        slot.close();
        slot.attach(calls::incrementAndGet);
        resources.close();
        slot.close();
        assertEquals(1, calls.get());
    }

    @Test public void cancellationBeforeStartupOwnsTheLateResource() {
        var resources = new OperationResources();
        var slot = resources.reserve();
        var calls = new AtomicInteger();
        resources.close();
        slot.attach(calls::incrementAndGet);
        resources.reserve().attach(calls::incrementAndGet);
        resources.close();
        assertEquals(2, calls.get());
    }

    @Test public void completedResourcesAreRemovedAndDependentsCloseFirst() {
        var resources = new OperationResources();
        List<String> order = new ArrayList<>();
        resources.reserve().attach(() -> order.add("server"));
        var done = resources.reserve();
        done.attach(() -> order.add("done"));
        resources.reserve().attach(() -> order.add("client"));
        done.close();
        resources.close();
        assertEquals(List.of("done", "client", "server"), order);
    }
}
