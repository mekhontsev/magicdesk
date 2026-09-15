package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public final class DesktopWidgetHostIdsTest {
    @Test public void workspaceIdentitySurvivesReopenButDoesNotFollowOutputsOrOrigins() {
        final Memory memory = new Memory();
        final DesktopWidgetHostIds ids = new DesktopWidgetHostIds(memory.preferences);
        final String phone = DesktopWidgetHostIds.workspaceKey(0, "local:phone");
        final String first = DesktopWidgetHostIds.workspaceKey(0, "virtual:first");
        final String second = DesktopWidgetHostIds.workspaceKey(0, "virtual:second");
        final int phoneId = ids.getOrAllocate(phone);
        final int firstId = ids.getOrAllocate(first);
        final int secondId = ids.getOrAllocate(second);
        assertNotEquals(phoneId, firstId);
        assertNotEquals(firstId, secondId);
        final DesktopWidgetHostIds reopened = new DesktopWidgetHostIds(memory.preferences);
        assertEquals(firstId, reopened.getOrAllocate(first));
        assertEquals(phoneId, reopened.getOrAllocate(phone));
        assertEquals(3, memory.writes);
        assertNotEquals(firstId, reopened.getOrAllocate(DesktopWidgetHostIds.workspaceKey(19, "virtual:first")));
    }

    @Test public void missingIdentityCannotAliasAnotherWorkspace() {
        assertThrows(IllegalArgumentException.class, () -> DesktopWidgetHostIds.workspaceKey(-1, "screen"));
        for (final String value : new String[]{null, "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> DesktopWidgetHostIds.workspaceKey(0, value));
        }
    }

    @Test public void allocationDoesNotPublishAnUnsavedId() {
        final Memory memory = new Memory();
        final DesktopWidgetHostIds ids = new DesktopWidgetHostIds(memory.preferences);
        memory.fail = true;
        assertThrows(IllegalStateException.class, () -> ids.getOrAllocate("0:screen"));
        assertFalse(memory.values.containsKey("host:0:screen"));
        assertThrows(IllegalStateException.class, () ->
                new DesktopWidgetHostIds(memory.preferences).getOrAllocate("0:screen"));
        assertFalse(memory.values.containsKey("host:0:screen"));
        memory.fail = false;
        final int id = ids.getOrAllocate("0:screen");
        assertEquals(id, new DesktopWidgetHostIds(memory.preferences).getOrAllocate("0:screen"));
    }

    @Test public void corruptOrExhaustedAllocatorDoesNotReuseExistingIds() {
        final Memory memory = new Memory();
        final DesktopWidgetHostIds ids = new DesktopWidgetHostIds(memory.preferences);
        memory.values.put("lastHostId", Integer.MAX_VALUE);
        assertThrows(IllegalStateException.class, () -> ids.getOrAllocate("0:screen"));
        memory.values.put("lastHostId", -1);
        assertThrows(IllegalStateException.class, () -> ids.getOrAllocate("0:screen"));
        memory.values.put("host:0:screen", -1);
        assertThrows(IllegalStateException.class, () -> ids.getOrAllocate("0:screen"));
    }

    private static final class Memory {
        final Map<String, Integer> values = new HashMap<>();
        boolean fail;
        int writes;
        final SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getInt")) return values.getOrDefault((String) args[0], (Integer) args[1]);
                    if (method.getName().equals("edit")) return editor();
                    throw new AssertionError(method);
                });

        private SharedPreferences.Editor editor() {
            final Map<String, Integer> pending = new HashMap<>();
            return (SharedPreferences.Editor) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class}, (proxy, method, args) -> {
                        if (method.getName().equals("putInt")) { pending.put((String) args[0], (Integer) args[1]); return proxy; }
                        if (method.getName().equals("remove")) { pending.put((String) args[0], null); return proxy; }
                        if (method.getName().equals("commit") || method.getName().equals("apply")) {
                            pending.forEach((key, value) -> {
                                if (value == null) values.remove(key); else values.put(key, value);
                            });
                            writes++;
                            return method.getName().equals("apply") ? null : !fail;
                        }
                        throw new AssertionError(method);
                    });
        }
    }
}
