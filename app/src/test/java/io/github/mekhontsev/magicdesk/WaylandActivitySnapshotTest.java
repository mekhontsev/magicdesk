package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class WaylandActivitySnapshotTest {
    @Test public void disappearingWindowUsesOneCatalogAndNextCallbackClosesHost() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
            static class WaylandSession {
                record Window(long id, boolean mapped, String title) {
                    Object constraints() { return null; }
                }
            }
            static class View { static final int VISIBLE=0, GONE=8; }
            static class Label { void setVisibility(int value) { } void setText(String value) { } }
            static class Surface { int getWidth() { return 0; } int getHeight() { return 0; } }
            static class Content { void constraints(Object value, float scale) { } }
            static class Presentation { boolean isClosed() { return false; } }
            static class Session {
                String name="Wayland";
                boolean desktop;
                int reads;
                Presentation presentation=new Presentation();
                List<WaylandSession.Window> catalog=List.of(new WaylandSession.Window(7,true,"Browser"));
                List<WaylandSession.Window> windows() {
                    reads++;
                    var result=catalog; catalog=List.of(); return result;
                }
                String state() { return "READY"; }
                String error() { return ""; }
                boolean stopped() { return false; }
                Object windowRecipe(long id) { return null; }
                void host(int task, long id) { }
                float unitScale(Object activity) { return 1; }
            }
            static class HostedStartupWindow { static boolean temporary(Object activity) { return false; } }
            static class GraphicalApplicationLaunch { static Object reference(Object activity,Object recipe) { return null; } }
            static class DesktopRuntimeBridge { static void refreshTaskPresentations() { } }
            static class R { static class string { static final int graphics_waiting_application=1; } }
            static class WaylandHostBinding {
                WaylandHostBinding(Object activity,Surface surface,Session session,long window) { }
                void close() { } void refresh() { }
            }
            Session session=new Session();
            long window=7;
            boolean pendingLaunch,finishing;
            Object identityRecipe,application;
            Label status=new Label(); Surface surface=new Surface(); Content content=new Content();
            WaylandHostBinding binding;
            String title;
            boolean isDestroyed() { return false; }
            boolean isFinishing() { return finishing; }
            void finishAndRemoveTask() { finishing=true; }
            int getTaskId() { return 1; }
            String getString(int id) { return "Waiting"; }
            void present(String value) { title=value; }
            void detachHostedWindow() { finishing=true; }
            public static void verify() {
                for (boolean pending : new boolean[]{false,true}) {
                    Fixture f=new Fixture(); f.pendingLaunch=pending; f.window=pending ? 0 : 7;
                    f.changed();
                    check(!f.finishing && f.session.reads==1 && "Browser".equals(f.title),
                            "one callback must use one retained catalog");
                    f.changed();
                    check(f.finishing && f.session.reads==2,"destroyed window retained its host");
                }
                Fixture pending=new Fixture(); pending.pendingLaunch=true; pending.window=0;
                pending.session.catalog=List.of(); pending.changed();
                check(!pending.finishing && pending.pendingLaunch,"empty pending launch was closed");
                Fixture desktop=new Fixture(); desktop.session.desktop=true;
                desktop.session.name="Distribution Desktop"; desktop.changed();
                check("Distribution Desktop".equals(desktop.title),"whole desktop exposed the nested compositor title");
            }
            """ + RuntimeSourceFixture.methods("WaylandActivity", "changed"));
    }
}
