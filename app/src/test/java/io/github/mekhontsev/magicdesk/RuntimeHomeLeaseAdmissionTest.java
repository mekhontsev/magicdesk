package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Exercises the actual admission methods with host-only HOME and storage dependencies. */
public final class RuntimeHomeLeaseAdmissionTest {
    @Test
    public void userLeaseRejectsIsolatedReuseWithoutChangingOwnership() throws Exception {
        verifyPolicyRejection("USER", "ISOLATED_SELF_TEST");
    }

    @Test
    public void isolatedLeaseRejectsUserReuseWithoutChangingOwnership() throws Exception {
        verifyPolicyRejection("ISOLATED_SELF_TEST", "USER");
    }

    @Test
    public void releasingLeaseCannotBecomeActiveAgain() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    DesktopDisplayTarget target=new DesktopDisplayTarget();
                    for (DesktopSessionPolicy policy : DesktopSessionPolicy.values()) {
                        State original=new State(0,new AndroidHomeSelection(),target,policy,
                                DesktopCompatibilityPolicy.NONE,Phase.RELEASING);
                        sStorage.state=original;
                        assertRejected(target, policy, "releasing", original);
                    }
                }
                """);
    }

    @Test
    public void repeatedSameUserLeaseRemainsIdempotent() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    DesktopDisplayTarget target=new DesktopDisplayTarget();
                    sStorage.state=new State(0,new AndroidHomeSelection(),target,
                            DesktopSessionPolicy.USER,DesktopCompatibilityPolicy.NONE,Phase.ACTIVE);
                    for (int attempt=0; attempt<2; attempt++) {
                        AcquireResult result=acquire(target);
                        check(!result.created, "same USER lease was reacquired");
                        check(result.state.policy==DesktopSessionPolicy.USER
                                && result.state.phase==Phase.ACTIVE, "same USER lease changed");
                    }
                    check(sBackend.claims==0, "idempotent admission reclaimed HOME");
                }
                """);
    }

    @Test
    public void preparedSamePolicyRemainsResumable() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    DesktopDisplayTarget target=new DesktopDisplayTarget();
                    for (DesktopSessionPolicy policy : DesktopSessionPolicy.values()) {
                        for (boolean alreadyClaimed : new boolean[]{false,true}) {
                            sBackend=new Backend();
                            State prepared=new State(0,new AndroidHomeSelection(),target,policy,
                                    DesktopCompatibilityPolicy.NONE,Phase.PREPARED);
                            sStorage.state=prepared;
                            sBackend.holder=alreadyClaimed ? MAGICDESK_PACKAGE : prepared.previousHome.packageName;
                            AcquireResult result=acquire(target,policy);
                            check(result.created!=alreadyClaimed, "prepared claim ownership changed");
                            check(result.state.policy==policy && result.state.phase==Phase.ACTIVE,
                                    "matching PREPARED lease was not resumed");
                            check(sBackend.claims==(alreadyClaimed ? 0 : 1), "prepared claim count changed");
                        }
                    }
                }
                """);
    }

    private static void verifyPolicyRejection(final String stored, final String requested)
            throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    DesktopDisplayTarget target=new DesktopDisplayTarget();
                    for (Phase phase : new Phase[]{Phase.ACTIVE,Phase.PREPARED}) {
                        State original=new State(0,new AndroidHomeSelection(),target,
                                DesktopSessionPolicy.%s,DesktopCompatibilityPolicy.NONE,phase);
                        sStorage.state=original;
                        assertRejected(target,DesktopSessionPolicy.%s,"policy",original);
                        sBackend.holder=original.previousHome.packageName;
                        assertRejected(target,DesktopSessionPolicy.%s,"policy",original);
                        sBackend.holder=MAGICDESK_PACKAGE;
                    }
                }
                """.formatted(stored, requested, requested));
    }

    private static String fixture() throws Exception {
        return """
                static final String MAGICDESK_PACKAGE="io.github.mekhontsev.magicdesk";
                static final Object LOCK=new Object();
                static boolean sPhoneOverviewRoutingActive;
                enum Phase { PREPARED, ACTIVE, RELEASING }
                enum DesktopSessionPolicy { USER, ISOLATED_SELF_TEST }
                static class DesktopCompatibilityPolicy {
                    static final DesktopCompatibilityPolicy NONE = new DesktopCompatibilityPolicy();
                }
                static class DesktopDisplayOutput {
                    enum Kind { PHONE, SIMULATED }
                    Kind kind=Kind.SIMULATED;
                }
                static class DesktopDisplayTarget {
                    int workspaceDisplayId=7;
                    DesktopDisplayOutput output=new DesktopDisplayOutput();
                    boolean isDefaultWorkspace() { return workspaceDisplayId==0; }
                    boolean sameBinding(DesktopDisplayTarget other) {
                        return other!=null && workspaceDisplayId==other.workspaceDisplayId
                                && output.kind==other.output.kind;
                    }
                }
                static class AndroidHomeSelection { String packageName="com.example.home"; }
                static class State {
                    int userId;
                    AndroidHomeSelection previousHome;
                    DesktopDisplayTarget target;
                    DesktopSessionPolicy policy; Phase phase;
                    DesktopCompatibilityPolicy compatibility;
                    State(int user, AndroidHomeSelection previous, DesktopDisplayTarget t,
                            DesktopSessionPolicy p, DesktopCompatibilityPolicy c, Phase ph) {
                        userId=user; previousHome=previous; target=t; policy=p; phase=ph;
                        compatibility=c;
                    }
                    DesktopDisplayTarget target() { return target; }
                """ + RuntimeSourceFixture.methods("DesktopHomeRoleLease", "matches", "withPhase") + "}\n"
                + """
                static class AcquireResult {
                    boolean created; State state;
                    AcquireResult(boolean c, State s) { created=c; state=s; }
                }
                static class Storage {
                    State state; int writes;
                    State read() { return state; }
                    void write(State s) { state=s; writes++; }
                }
                static class Backend {
                    int calls, claims;
                    String holder=MAGICDESK_PACKAGE;
                    int currentUserId() { return 0; }
                    String getHomePackage(int user) { calls++; return holder; }
                    void selectHomeSurface(Object surface) throws IOException { calls++; }
                    void presentHome(int user, String holder) { calls++; }
                    void setHomePackage(int user, String packageName) { calls++; claims++; holder=packageName; }
                }
                static class DesktopHomeSurfaceRouter { enum Surface { PHONE } }
                static class PackageNameValidator { static boolean isSafe(String p) { return true; } }
                static Storage sStorage=new Storage(); static Backend sBackend=new Backend();
                static DesktopHomeSurfaceRouter.Surface surfacesFor(State s) { return DesktopHomeSurfaceRouter.Surface.PHONE; }
                static void restorePreparedLease(State s, IOException e) { throw new AssertionError("unexpected branch"); }
                static AndroidHomeSelection resolvePreviousHome(int user, String p) { throw new AssertionError("unexpected branch"); }
                static void assertRejected(DesktopDisplayTarget target,DesktopSessionPolicy policy,
                        String diagnostic,State original) throws Exception {
                    boolean previousRouting=sPhoneOverviewRoutingActive;
                    try {
                        acquire(target,policy);
                        throw new AssertionError("incompatible HOME lease admitted: "+diagnostic);
                    } catch (IOException expected) {
                        check(expected.getMessage().contains(diagnostic), "missing admission diagnostic");
                        check(sStorage.state==original && sStorage.writes==0, "original lease was replaced");
                        check(sBackend.calls==0, "rejected admission reached HOME backend");
                        check(sPhoneOverviewRoutingActive==previousRouting, "rejected admission changed overview routing");
                    }
                }
                static AcquireResult acquire(DesktopDisplayTarget target) throws IOException {
                    return acquire(target, DesktopSessionPolicy.USER);
                }
                static AcquireResult acquire(DesktopDisplayTarget target, DesktopSessionPolicy policy)
                        throws IOException {
                    return activate(prepare(target, policy, DesktopCompatibilityPolicy.NONE));
                }
                """ + RuntimeSourceFixture.methods("DesktopHomeRoleLease", "prepare", "requireTarget",
                        "shouldPresentMagicDeskHome", "activate", "claim", "requireHolder");
    }
}
