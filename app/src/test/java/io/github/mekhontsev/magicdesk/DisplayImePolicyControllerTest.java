package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public final class DisplayImePolicyControllerTest {
    @Test
    public void phoneAndInactiveSessionsDoNotChangePolicy() throws Exception {
        final FakeApi api = new FakeApi();
        final DisplayImePolicyController policy = new DisplayImePolicyController(api);
        policy.configure(0);
        policy.configure(-1);
        policy.close();
        assertEquals(0, api.reads);
        assertEquals(List.of(), api.writes);
    }

    @Test
    public void restoresPreviousPolicyAndDoesNotPollAnActiveDisplay() throws Exception {
        final FakeApi api = new FakeApi();
        final DisplayImePolicyController policy = new DisplayImePolicyController(api);
        policy.configure(2);
        final int reads = api.reads;
        policy.configure(2);
        assertEquals(reads, api.reads);
        policy.configure(-1);
        policy.close();
        assertEquals(List.of("2=1", "2=0"), api.writes);
    }

    @Test
    public void existingFallbackRequiresNoWriteOrRestore() throws Exception {
        final FakeApi api = new FakeApi();
        api.values.put(2, 1);
        final DisplayImePolicyController policy = new DisplayImePolicyController(api);
        policy.configure(2);
        policy.close();
        assertEquals(List.of(), api.writes);
    }

    @Test
    public void switchingDisplayRestoresBeforeAcquiringTheNext() throws Exception {
        final FakeApi api = new FakeApi();
        api.values.put(3, 2);
        final DisplayImePolicyController policy = new DisplayImePolicyController(api);
        policy.configure(2);
        policy.configure(3);
        policy.close();
        assertEquals(List.of("2=1", "2=0", "3=1", "3=2"), api.writes);
    }

    @Test
    public void preservesAChangeMadeByAnotherOwner() throws Exception {
        final FakeApi api = new FakeApi();
        final DisplayImePolicyController policy = new DisplayImePolicyController(api);
        policy.configure(2);
        api.values.put(2, 2);
        policy.configure(2);
        policy.close();
        assertEquals(Integer.valueOf(2), api.values.get(2));
        assertEquals(List.of("2=1"), api.writes);
    }

    @Test
    public void failedAcknowledgementStillRetainsRestorationOwnership() throws Exception {
        final FakeApi api = new FakeApi();
        final DisplayImePolicyController policy = new DisplayImePolicyController(api);
        api.failAfterWrite = true;
        assertThrows(ReflectiveOperationException.class, () -> policy.configure(2));
        policy.configure(2);
        policy.close();
        assertEquals(List.of("2=1", "2=0"), api.writes);
    }

    @Test
    public void failedRestorationCanBeRetried() throws Exception {
        final FakeApi api = new FakeApi();
        final DisplayImePolicyController policy = new DisplayImePolicyController(api);
        policy.configure(2);
        api.failBeforeWrite = true;
        assertThrows(IllegalStateException.class, policy::close);
        policy.close();
        assertEquals(List.of("2=1", "2=0"), api.writes);
    }

    private static final class FakeApi implements DisplayImePolicyController.Api {
        final Map<Integer, Integer> values = new HashMap<>();
        final List<String> writes = new ArrayList<>();
        int reads;
        boolean failBeforeWrite;
        boolean failAfterWrite;

        @Override
        public int get(final int displayId) {
            ++reads;
            return values.getOrDefault(displayId, 0);
        }

        @Override
        public void set(final int displayId, final int policy)
                throws ReflectiveOperationException {
            if (failBeforeWrite) {
                failBeforeWrite = false;
                throw new ReflectiveOperationException("write failed");
            }
            values.put(displayId, policy);
            writes.add(displayId + "=" + policy);
            if (failAfterWrite) {
                failAfterWrite = false;
                throw new ReflectiveOperationException("acknowledgement lost");
            }
        }
    }
}
