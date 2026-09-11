package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class AndroidActivityResolutionTest {
    @Test public void launcherResolutionDoesNotRequireDefaultButOrdinaryIntentsDo() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Intent {
                    static final String ACTION_MAIN="main", CATEGORY_LAUNCHER="launcher", CATEGORY_LEANBACK_LAUNCHER="tv";
                    final String action; final Set<String> categories;
                    Intent(String action, String... categories) { this.action=action; this.categories=Set.of(categories); }
                    String getAction() { return action; }
                    boolean hasCategory(String c) { return categories.contains(c); }
                }
                static class PackageManager { static final int MATCH_DEFAULT_ONLY=65536; }
                """ + RuntimeSourceFixture.methods("AndroidActivityResolution", "queryFlags", "isLauncherEntry") + """
                public static void verify() {
                    check(queryFlags(new Intent("main", "launcher")) == 0, "launcher without DEFAULT");
                    check(queryFlags(new Intent("main", "tv")) == 0, "TV launcher without DEFAULT");
                    check(queryFlags(new Intent("view")) == 65536, "ordinary VIEW retains default filtering");
                    check(queryFlags(new Intent("view", "launcher")) == 65536, "MAIN is required");
                    check(queryFlags(new Intent("main", "home")) == 65536, "HOME is not an app launcher entry");
                    check(queryFlags(new Intent(null)) == 65536, "explicit activity without action");
                }
                """);
    }
}
