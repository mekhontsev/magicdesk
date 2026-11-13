package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ShellActivityStartControllerTest {
    @Test public void safetyRejectionRetainsCauseWithoutFailingOpen() throws Exception {
        RuntimeSourceFixture.verify("""
                record Intent() { }
                interface Listener { boolean onActivityStarting(Intent intent, String packageName); }
                boolean mEnabled = true;
                Listener[] mListeners;
                final List<String> errors = new ArrayList<>();
                final List<Boolean> observations = new ArrayList<>();
                void report(String error) { errors.add(error); }
                void notifyActivityStart(Intent intent, String name, boolean allowed) { observations.add(allowed); }
                """ + RuntimeSourceFixture.nestedClass("ShellActivityStartController", "StartRejectedException")
                + RuntimeSourceFixture.methods("ShellActivityStartController", "allowActivityStart", "usefulMessage")
                + """
                public static void verify() {
                    Fixture f = new Fixture();
                    f.mListeners = new Listener[] {
                        (intent, pkg) -> { throw new StartRejectedException("fullscreen display=5 package=" + pkg,
                                new IllegalStateException("fullscreen separator missing for area=100")); },
                        (intent, pkg) -> { throw new AssertionError("veto was ignored"); }
                    };
                    check(!f.allowActivityStart(new Intent(), "settings"), "unsafe launch accepted");
                    check(f.errors.size() == 1 && f.errors.get(0).contains("display=5 package=settings")
                            && f.errors.get(0).contains("separator missing for area=100"), "rejection cause lost");
                    check(f.observations.equals(List.of(false)), "veto not observed");
                    f.mEnabled = false;
                    check(f.allowActivityStart(new Intent(), "settings") && f.errors.size() == 1,
                            "closed controller blocked a launch");
                    f.mEnabled = true;
                    f.mListeners = new Listener[] {(intent, pkg) -> { throw new IllegalStateException("optional observer failed"); }};
                    check(f.allowActivityStart(new Intent(), "settings"), "optional observer became a veto");
                    check(f.observations.equals(List.of(false, true)), "optional failure not observed");
                    f.mListeners = new Listener[] {(intent, pkg) -> false};
                    check(!f.allowActivityStart(new Intent(), "settings"), "ordinary policy veto ignored");
                }
                """);
    }
}
