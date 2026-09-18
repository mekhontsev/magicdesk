package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** One working-state owner per UID, shared by all active work leases. */
final class BackgroundWorkProtection implements AutoCloseable {
    private final PlatformBackgroundWork mPlatform;
    private final Map<Object, Set<Integer>> mClaims = new LinkedHashMap<>();
    private final Map<Integer, PlatformBackgroundWork.Session> mSessions = new LinkedHashMap<>();

    BackgroundWorkProtection(PlatformBackgroundWork platform) { mPlatform = platform; }

    void retain(Object owner, Set<Integer> uids) throws Exception {
        final Set<Integer> next = new LinkedHashSet<>(mClaims.getOrDefault(owner, Set.of()));
        for (int uid : uids) if (uid >= 10000) next.add(uid);
        final Map<Integer, PlatformBackgroundWork.Session> acquired = new LinkedHashMap<>();
        try {
            for (int uid : next) if (!mSessions.containsKey(uid)) acquired.put(uid, mPlatform.begin(uid));
        } catch (Exception error) {
            for (PlatformBackgroundWork.Session session : acquired.values()) session.close();
            throw error;
        }
        mSessions.putAll(acquired);
        mClaims.put(owner, next);
    }

    Set<Integer> uids(Object owner) { return Set.copyOf(mClaims.getOrDefault(owner, Set.of())); }

    void release(Object owner) {
        mClaims.remove(owner);
        final Set<Integer> retained = new LinkedHashSet<>();
        for (Set<Integer> claim : mClaims.values()) retained.addAll(claim);
        mSessions.entrySet().removeIf(entry -> {
            if (retained.contains(entry.getKey())) return false;
            entry.getValue().close();
            return true;
        });
    }

    void refresh() throws Exception {
        for (PlatformBackgroundWork.Session session : mSessions.values()) session.refresh();
    }

    @Override public void close() {
        for (PlatformBackgroundWork.Session session : mSessions.values()) session.close();
        mSessions.clear();
        mClaims.clear();
    }
}
