package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class X11ApplicationIdentityTest {
    @Test public void sharedServerRetainsIndividualRecipesAndHostTasks() throws Exception {
        RuntimeSourceFixture.verify("""
            record Application(Fixture session, long window) { }
            record Recipe(String key) { Shortcut shortcut() { return new Shortcut(); } }
            static class Shortcut { Options graphics = new Options(); }
            static class Options { String startupClass() { return "writer"; } }
            static class X11Session {
                record Window(long id, String cls, boolean applicationWindow) {
                    Window(long id, String cls) { this(id, cls, true); }
                    boolean matchesClass(String expected) { return cls.equals(expected); }
                }
            }
            Recipe recipe = new Recipe("writer");
            boolean application = true, hadApplicationWindow;
            Map<Long, Recipe> windowRecipes = new LinkedHashMap<>();
            Map<Integer, Long> hosts = new LinkedHashMap<>();
            enum RecentLaunchScope { DESKTOP }
            long recordedWindow;
            void recordUse(long window, RecentLaunchScope scope) { recordedWindow = window; }
            public static void verify() {
                var f = new Fixture();
                check(f.findApplication("writer").window() == 0, "pending launch can be reused");
                f.windowRecipes.put(1L, f.recipe);
                f.windowRecipes.put(2L, new Recipe("calc"));
                f.hadApplicationWindow = true;
                f.host(100, 1, true);
                f.host(200, 2, true);
                f.host(100, 1, true);
                check(f.hostTaskId() == 100, "Writer is the most recent host");
                var calc = f.findApplication("calc");
                check(calc.window() == 2 && f.hostTaskId(calc.window()) == 200, "Calc does not activate Writer");
                check(f.recordTaskUse(200, RecentLaunchScope.DESKTOP) && f.recordedWindow == 2,
                        "task activation records Calc, not the server's primary recipe");
                check(!f.recordTaskUse(999, RecentLaunchScope.DESKTOP), "unrelated task has no session recipe");
                f.host(300, 2, true);
                f.releaseHost(300);
                check(f.hostTaskId(2) == 200, "remaining exact host can be reused");
                f.releaseHost(200);
                check(f.hostTaskId(2) == -1 && f.findApplication("calc").window() == 2,
                        "hostless client reopens its own output");
                f.windowRecipes.remove(1L);
                check(f.findApplication("writer") == null, "closed Writer cannot reuse another document");
                f.application = false;
                check(f.findApplication("writer").window() == 0, "whole desktop retains its recipe");
                f.application = true;
                f.associateRecipes(List.of(new X11Session.Window(2, "calc"), new X11Session.Window(3, "writer")));
                check(f.findApplication("writer").window() == 3, "main document after recovery keeps launch identity");
                check(f.findApplication("calc").window() == 2, "catalog does not overwrite a forwarded recipe");
                f.associateRecipes(List.of(new X11Session.Window(2, "calc"), new X11Session.Window(3, "writer"),
                        new X11Session.Window(4, "writer", false)));
                check(!f.windowRecipes.containsKey(4L) && f.findApplication("writer").window() == 3,
                        "a dialog with the same class must not inherit launch/geometry identity");
                f.windowRecipes.put(4L, f.recipe);
                f.associateRecipes(List.of(new X11Session.Window(3, "writer"), new X11Session.Window(4, "writer", false)));
                check(!f.windowRecipes.containsKey(4L), "later dialog classification must remove an earlier association");
                var pending = new Fixture();
                pending.associateRecipes(List.of(new X11Session.Window(5, "writer", false), new X11Session.Window(6, "document")));
                check(!pending.windowRecipes.containsKey(5L) && pending.windowRecipes.containsKey(6L),
                        "startup dialog must not consume the initial application recipe");
            }
            """ + RuntimeSourceFixture.methods("X11Sessions", "findApplication", "associateRecipes", "host", "hostTaskId", "releaseHost", "recordTaskUse"));
    }
}
