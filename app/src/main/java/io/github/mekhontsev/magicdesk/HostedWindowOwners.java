package io.github.mekhontsev.magicdesk;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Main-thread arbitration when multiple Android hosts borrow one native window. */
final class HostedWindowOwners {
    private final Map<Long, Object> owners = new HashMap<>();
    boolean claim(long window, Object host) { return owners.computeIfAbsent(window, ignored -> host) == host; }
    boolean owns(long window, Object host) { return owners.get(window) == host; }
    boolean release(Object host) { return owners.values().removeIf(value -> value == host); }
    void retain(Collection<Long> windows) { owners.keySet().retainAll(windows); }
}
