package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class AppPresentationActivityCompletionTest {
    @Test public void newIntentPackageSurvivesOlderMutationCompletion() throws Exception { verify("example.newapp"); }
    @Test public void newIntentListSurvivesOlderMutationCompletion() throws Exception { verify(null); }
    @Test public void samePackageRefreshesAfterMutation() throws Exception { verify("example.oldapp"); }

    private static void verify(final String current) throws Exception {
        RuntimeSourceFixture.verify("""
                record AppIdentity(long serial, String name) {
                    static AppIdentity fromPersistentKey(String key) { return new AppIdentity(0, key); }
                }
                static class AppProfile { static void requireCurrent(Object c, AppIdentity a) {} }
                static class AppPresentationProfileManager { static void requireUserApplication(AppIdentity a) {} }
                void finish() {}
                static class Intent { String value; Intent(String value) { this.value = value; } String getStringExtra(String key) { return value; } }
                static class PackageNameValidator { static boolean isSafe(String name) { return name != null; } }
                static class BuildConfig { static final String APPLICATION_ID = "io.github.mekhontsev.magicdesk"; }
                static class TaskRepository { record ActionResult(boolean success, String message) {} }
                static class Toast { static final int LENGTH_LONG = 1; static Toast makeText(Object c, String m, int d) { return new Toast(); } void show() {} }
                static class AppPresentationSettingsView {
                    boolean enabled = true;
                    AppPresentationSettingsView(Object activity, Object actions) {}
                    Object createList() { return null; } Object createDetail(AppIdentity application) { return null; }
                    void setEnabled(boolean value) { enabled = value; }
                }
                AppIdentity mApplication; boolean mApplying = true, mReturnToList;
                AppPresentationSettingsView mView;
                static final String EXTRA_APPLICATION = "application";
                boolean isFinishing() { return false; } boolean isDestroyed() { return false; }
                void setContentView(Object view) {}
                public static void verify() {
                    Fixture f = new Fixture(); String current =
                """ + (current == null ? "null;" : "\"" + current + "\";") + """
                    f.renderIntent(new Intent(current), false);
                    f.finishMutation(AppIdentity.fromPersistentKey("example.oldapp"), new TaskRepository.ActionResult(true, "saved"));
                    check(Objects.equals(current == null ? null : AppIdentity.fromPersistentKey(current), f.mApplication), "old mutation replaced current package/list");
                    check(f.mView.enabled && !f.mApplying, "current view remained disabled");
                }
                """ + RuntimeSourceFixture.methods("AppPresentationSettingsActivity", "finishMutation",
                "renderIntent", "renderList", "renderPackage"));
    }
}
