package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class WaylandApplicationIdentityTest {
    @Test public void applicationLifetimeFollowsWindowsWhileManagerSessionsRemainRetained() throws Exception {
        RuntimeSourceFixture.verify("""
            static class WaylandSession { record Window(long id, boolean mapped) { } }
            static class Looper {
                static Object getMainLooper() { return null; }
                static Object myLooper() { return null; }
            }
            static class Main {
                int cancellations;
                void post(Runnable action) { action.run(); }
                void removeCallbacks(Runnable action) { cancellations++; }
            }
            interface Listener { void changed(); }
            static class Presentation {
                Set<Long> retained=Set.of(); List<Long> presented=new ArrayList<>();
                void retain(Set<Long> ids) { retained=ids; presented.clear(); }
                void present(long id) { presented.add(id); }
            }
            Main MAIN=new Main();
            Runnable applicationTimeout=()->{};
            Object recentScope=new Object();
            int recorded;
            boolean application=true,hadWindows,closed;
            boolean ready() { return !closed; }
            void recordUse(Object scope) { recorded++; }
            void close() { closed=true; }
            List<WaylandSession.Window> catalog=List.of();
            List<WaylandSession.Window> windows() { return catalog; }
            List<Listener> listeners=new ArrayList<>();
            Presentation presentation=new Presentation();
            public static void verify() {
                Fixture f=new Fixture();
                f.changed();
                check(!f.closed && !f.hadWindows && f.recorded==0,"pending launch closed or recorded too early");
                f.catalog=List.of(new WaylandSession.Window(1,true)); f.changed();
                check(f.hadWindows && f.recorded==1 && f.MAIN.cancellations==1,"first window did not complete launch");
                f.catalog=List.of(new WaylandSession.Window(1,true),new WaylandSession.Window(2,true)); f.changed();
                check(f.recorded==1 && f.presentation.presented.equals(List.of(1L,2L)),"child window restarted launch");
                f.catalog=List.of(new WaylandSession.Window(1,false)); f.changed();
                check(!f.closed && f.presentation.retained.equals(Set.of(1L)) && f.presentation.presented.isEmpty(),
                        "unmapping destroyed an existing client");
                f.catalog=List.of(); f.changed();
                check(f.closed,"last destroyed window left an application session running");
                Fixture manager=new Fixture(); manager.application=false; manager.hadWindows=true; manager.changed();
                check(!manager.closed,"empty manager session was stopped");
            }
            """ + RuntimeSourceFixture.methods("WaylandSessions", "changed"));
    }

    @Test public void pendingAndHostlessApplicationsKeepOneRecipeAndDeletedLaunchersStayDeleted() throws Exception {
        RuntimeSourceFixture.verify("""
            static class WaylandSession {
                record Window(long id, boolean mapped) { }
            }
            record Recipe(String key, String termuxPackage, String sourcePath) { }
            static class Presentation { Set<Long> claimed=new HashSet<>(); void claim(long id) { claimed.add(id); } }
            Recipe recipe=new Recipe("editor", "com.termux", "/editor.desktop");
            Map<Integer,Long> hosts=new LinkedHashMap<>();
            Presentation presentation=new Presentation();
            boolean closed,hadWindows;
            boolean stopped() { return closed; }
            List<WaylandSession.Window> catalog=List.of();
            List<WaylandSession.Window> windows() { return catalog; }
            public static void verify() {
                Fixture f=new Fixture();
                check(f.recipeWindow("other")==-1,"wrong recipe reused");
                check(f.recipeWindow("editor")==0,"pending launch lost");
                f.host(100,0);
                check(f.hostTaskId(0)==100,"pending task not reused");
                f.catalog=List.of(new WaylandSession.Window(1,true),new WaylandSession.Window(2,true));
                f.hadWindows=true; f.host(100,1); f.host(200,2);
                check(f.recipeWindow("editor")==1 && f.hostTaskId(1)==100,"application identity lost");
                f.releaseHost(100);
                check(f.recipeWindow("editor")==1 && f.hostTaskId(1)==-1,"hostless client cannot reopen");
                f.forgetRecipe("other.termux","/editor.desktop");
                check(f.recipe!=null,"another executor deleted identity");
                f.forgetRecipe("com.termux","/editor.desktop");
                check(f.recipe==null && f.recipeWindow("editor")==-1,"deleted recipe resurrected");
                check(f.hostTaskId(2)==200 && !f.closed,"shortcut deletion affected live clients");
                f.recipe=new Recipe("editor","com.termux","/editor.desktop");
                f.catalog=List.of();
                check(f.recipeWindow("editor")==-1,"finished application became pending");
                f.closed=true; f.hadWindows=false;
                check(f.recipeWindow("editor")==-1,"closed session reused");
            }
            """ + RuntimeSourceFixture.methods("WaylandSessions", "recipeWindow", "host", "hostTaskId", "releaseHost", "forgetRecipe"));
    }
}
