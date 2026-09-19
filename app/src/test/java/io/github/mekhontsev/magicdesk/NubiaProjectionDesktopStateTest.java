package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class NubiaProjectionDesktopStateTest {
    @Test public void ownsTheExternalSessionGroupAndRecoversWithoutDesktop() throws Exception {
        RuntimeSourceFixture.verify("""
                private Set<NubiaCaptionVisibilityManager.Transport> mTransports = Set.of();
                private Boolean mSessionEnabled;
                private boolean mLastSuccess = true;
                private String mError = "";
                static final List<String> events = new ArrayList<>();
                static boolean configured = true, failRelease;
                boolean isEnabled() { return configured; }
                static class NubiaProjectionPackageLease {
                    void release() throws IOException {
                        events.add("release");
                        if (failRelease) throw new IOException("unavailable");
                    }
                    void acquire() { events.add("acquire"); }
                }
                static NubiaProjectionPackageLease lease() { return new NubiaProjectionPackageLease(); }
                static class NubiaCaptionVisibilityManager {
                    enum Transport { WIRED, WIRELESS }
                    static boolean setTransports(Set<Transport> targets) { events.add("captions"); return true; }
                }
                static class CompatibilityDiagnostics { static void record(Object... args) {} }
                public static void verify() {
                    Fixture f = new Fixture();
                    var wired = Set.of(NubiaCaptionVisibilityManager.Transport.WIRED);
                    var both = Set.of(NubiaCaptionVisibilityManager.Transport.WIRED,
                            NubiaCaptionVisibilityManager.Transport.WIRELESS);
                    check(f.setTransports(wired), "acquire failed");
                    check(events.equals(List.of("release", "captions", "acquire")), "provider ordering");
                    events.clear(); configured = false;
                    f.setTransports(wired); f.recover();
                    check(events.isEmpty(), "reconciliation/reconnect reset a live lease");
                    f.setTransports(both);
                    check(events.equals(List.of("release", "captions", "acquire")), "session option changed early");
                    events.clear(); f.setTransports(wired);
                    check(events.contains("acquire"), "last remaining transport lost protection");
                    events.clear(); f.setTransports(Set.of());
                    check(events.equals(List.of("release", "captions")), "final release ordering");
                    events.clear(); f.setTransports(wired);
                    check(events.equals(List.of("release", "captions")), "next-session opt out ignored");
                    failRelease = true; events.clear();
                    check(!f.setTransports(Set.of()), "failure reported success");
                    check(events.equals(List.of("release")), "called provider before package recovery");
                    failRelease = false; events.clear(); f.recover();
                    check(events.equals(List.of("release", "captions")), "failed close not recovered");
                    events.clear(); new Fixture().recover();
                    check(events.equals(List.of("release", "captions")), "startup acquired a new session");
                }
                """ + RuntimeSourceFixture.methods("platform/nubia/NubiaProjectionDesktopState",
                        "setTransports", "recover"));
    }
}
