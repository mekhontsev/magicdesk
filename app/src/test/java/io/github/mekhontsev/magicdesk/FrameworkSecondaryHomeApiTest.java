package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FrameworkSecondaryHomeApiTest {
    @Test
    public void capturesResolvedHandlerOrAndroidConfiguredFallback() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    Fixture api = new Fixture();
                    Context context = new Context();
                    api.current = component("chosen/.Secondary");
                    check(api.capture(context).equals("chosen/.Secondary"), "preserve selected launcher");
                    api.current = null;
                    check(api.capture(context).equals("system/.Secondary"), "Android system fallback");
                    api.current = component(BuildConfig.APPLICATION_ID + "/.DesktopActivity");
                    check(api.capture(context).equals("system/.Secondary"), "never save ourselves");
                    api.system = null;
                    try { api.capture(context); throw new AssertionError("unresolved system fallback"); }
                    catch (IllegalStateException expected) { }
                    api.system = component("system/.Secondary");
                    context.resources.packageName = "";
                    try { api.capture(context); throw new AssertionError("missing system configuration"); }
                    catch (IllegalStateException expected) { }
                }
                """);
    }

    @Test
    public void restoreReplacesEvenAnInvisibleStalePreference() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    Fixture api = new Fixture();
                    Context context = new Context();
                    api.current = component("system/.Secondary");
                    api.available = List.of(new ResolveInfo("chosen", ".Secondary"));
                    api.restore(context, "chosen/.Secondary");
                    check(api.replaced.equals(component("chosen/.Secondary")), "saved available handler");
                    api.available = List.of();
                    api.restore(context, "removed/.Secondary");
                    check(api.replaced.equals(component("system/.Secondary")), "removed launcher fallback");
                    api.replaced = null;
                    try {
                        api.restore(context, BuildConfig.APPLICATION_ID + "/.DesktopActivity");
                        throw new AssertionError("must not restore ourselves");
                    } catch (IllegalArgumentException expected) { }
                    check(api.replaced == null, "invalid snapshot did not change preference");
                }
                """);
    }

    private static String fixture() throws Exception {
        return """
                @interface SuppressLint { String value(); }
                static class BuildConfig { static final String APPLICATION_ID = "magicdesk"; }
                static class PackageNameValidator {
                    static boolean isSafe(String value) { return value != null && !value.isEmpty(); }
                }
                record ComponentName(String packageName, String name) {
                    String getPackageName() { return packageName; }
                    String flattenToString() { return packageName + "/" + name; }
                    static ComponentName unflattenFromString(String value) {
                        String[] parts = value.split("/");
                        return parts.length == 2 ? new ComponentName(parts[0], parts[1]) : null;
                    }
                }
                static ComponentName component(String value) { return ComponentName.unflattenFromString(value); }
                static class Intent {
                    static final String ACTION_MAIN = "MAIN", CATEGORY_SECONDARY_HOME = "SECONDARY_HOME";
                    String packageName;
                    Intent(String action) { check(ACTION_MAIN.equals(action), "MAIN action"); }
                    Intent addCategory(String category) {
                        check(CATEGORY_SECONDARY_HOME.equals(category), "secondary category only"); return this;
                    }
                    Intent setPackage(String value) { packageName = value; return this; }
                }
                static class ActivityInfo { String packageName, name; }
                static class ResolveInfo {
                    ActivityInfo activityInfo = new ActivityInfo();
                    ResolveInfo(String p, String n) { activityInfo.packageName = p; activityInfo.name = n; }
                }
                static class Context {
                    Resources resources = new Resources();
                    Resources getResources() { return resources; }
                }
                static class Resources {
                    String packageName = "system";
                    int getIdentifier(String key, String kind, String owner) {
                        check(key.equals("config_secondaryHomePackage") && kind.equals("string")
                                && owner.equals("android"), "use Android configuration"); return 1;
                    }
                    String getString(int id) { return packageName; }
                }
                ComponentName current, replaced, system = component("system/.Secondary");
                List<ResolveInfo> available = List.of();
                ComponentName selected(Intent intent) {
                    return intent.packageName == null ? current : system;
                }
                List<ResolveInfo> candidates(Intent intent) { return available; }
                void replace(ComponentName value) { replaced = value; }
                """ + RuntimeSourceFixture.methods("FrameworkSecondaryHomeApi",
                        "capture", "restore", "systemHome", "intent", "component", "isOurs");
    }
}
