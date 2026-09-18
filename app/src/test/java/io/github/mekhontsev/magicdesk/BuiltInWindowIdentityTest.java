package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class BuiltInWindowIdentityTest {
    @Test public void liveIdentityRequiresMatchingTaskPackageProfileAndHost() throws Exception {
        RuntimeSourceFixture.verify("""
            record AppIdentity(String packageName) { }
            static class AppReference {
                AppIdentity application = new AppIdentity("host");
                Object builtIn = "x11";
                String recipe;
                AppReference(String recipe) { this.recipe = recipe; }
                AppReference windowStateKey() { return recipe == null ? null : this; }
            }
            interface ApplicationSource { AppReference windowApplication(); }
            static class Activity {
                boolean destroyed;
                int getTaskId() { return 17; }
                String getPackageName() { return "host"; }
                boolean isDestroyed() { return destroyed; }
            }
            static class WindowActivity extends Activity implements ApplicationSource {
                AppReference identity = new AppReference("calc");
                public AppReference windowApplication() { return identity; }
            }
            static class AppProfile {
                static AppProfile current(Activity ignored) { return new AppProfile(); }
                boolean owns(int user) { return user == 2; }
            }
            static List<java.lang.ref.WeakReference<Activity>> WINDOWS = new ArrayList<>();
            """ + RuntimeSourceFixture.methods("BuiltInWindowRegistry", "resolveWindowApplication")
                    .replace("WeakReference<", "java.lang.ref.WeakReference<") + """
            public static void verify() {
                var host = new AppReference(null);
                var activity = new WindowActivity();
                WINDOWS.add(new java.lang.ref.WeakReference<>(activity));
                check(resolveWindowApplication(17, 2, host) == activity.identity, "exact host recipe");
                check(resolveWindowApplication(18, 2, host) == null, "not another task");
                check(resolveWindowApplication(17, 3, host) == null, "not another profile");
                activity.identity.builtIn = "terminal";
                check(resolveWindowApplication(17, 2, host) == null, "not another built-in");
                activity.identity.builtIn = "x11";
                activity.destroyed = true;
                check(resolveWindowApplication(17, 2, host) == null, "not a destroyed host");
                WINDOWS.clear();
                var ordinary = new AppReference("ordinary");
                check(resolveWindowApplication(19, 2, ordinary) == ordinary, "ordinary fallback");
                check(resolveWindowApplication(17, 2, null) == null, "unknown profile stays unknown");
            }
            """);
    }
}
