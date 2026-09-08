package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.lang.reflect.ParameterizedType;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class AppProfileStorageTest {
    private static final AppProfile PERSONAL = new AppProfile(0, 5);
    private static final AppProfile WORK = new AppProfile(10, 19);
    private static final AppLaunchTarget TARGET = AppLaunchTarget.packageDefault("example.app");
    private static final AppReference PERSONAL_APP = PERSONAL.reference(TARGET);
    private static final AppReference WORK_APP = WORK.reference(TARGET);

    @Before public void setUp() {
        DesktopStateStore.useStorageForTests(new DesktopStateStore.Storage() {
            private String encoded = "";
            @Override public String read() { return encoded; }
            @Override public void write(String value) { encoded = value; }
        });
    }

    @After public void tearDown() {
        AppWindowStateStore.clearPendingModeUpdatesForTests();
        DesktopStateStore.useStorageForTests(null);
    }

    @Test public void pinsHistoryBoundsAndDensityKeepProfilesSeparate() throws Exception {
        DesktopPreferences.saveTaskbarApps(List.of(PERSONAL_APP, WORK_APP, PERSONAL_APP));
        assertEquals(List.of(PERSONAL_APP, WORK_APP), DesktopPreferences.taskbarApps());

        final List<AppReference> recent = DesktopPreferences.updateRecentApps(
                List.of(PERSONAL_APP, WORK_APP), WORK_APP, 24);
        assertEquals(List.of(WORK_APP, PERSONAL_APP), recent);
        assertEquals(recent, DesktopPreferences.decodeRecentApps(
                DesktopPreferences.encodeRecentApps(recent)));

        assertTrue(AppWindowStateStore.rememberWindowed(PERSONAL_APP,
                new RelativeWindowBounds(0, 0, 5000, 10000)));
        assertTrue(AppWindowStateStore.rememberMode(WORK_APP, AppWindowState.Mode.FULLSCREEN));
        assertTrue(AppPresentationProfileStore.setScale(PERSONAL_APP.application, 100));
        assertTrue(AppPresentationProfileStore.setScale(WORK_APP.application, 150));

        final DesktopStateStore.State state = DesktopStateStore.decode(
                DesktopStateStore.encode(DesktopStateStore.read(s -> s, null)));
        assertEquals(AppWindowState.Mode.WINDOWED, state.appWindows.get(PERSONAL_APP).mode);
        assertEquals(AppWindowState.Mode.FULLSCREEN, state.appWindows.get(WORK_APP).mode);
        assertEquals(100, state.appPresentations.get(PERSONAL_APP.application).scalePercent);
        assertEquals(150, state.appPresentations.get(WORK_APP.application).scalePercent);
        assertTrue(AppPresentationProfileStore.reset(PERSONAL_APP.application));
        assertEquals(150, AppPresentationProfileStore.load(WORK_APP.application).scalePercent);
    }

    @Test public void recycledUserIdCannotReuseWindowState() {
        final AppReference replacement = new AppProfile(WORK.userId, 27).reference(TARGET);
        assertTrue(AppWindowStateStore.rememberMode(WORK_APP, AppWindowState.Mode.FULLSCREEN));
        assertNull(AppWindowStateStore.load(replacement));
    }

    @Test public void builtInsHaveProfileScopedCanonicalReferences() {
        final AppReference files = PERSONAL.reference(BuiltInDesktopAppCatalog.filesTarget());
        final AppReference settings = PERSONAL.reference(BuiltInDesktopAppCatalog.settingsTarget());
        assertNotEquals(files, settings);
        assertNotEquals(files, WORK.reference(BuiltInDesktopAppCatalog.filesTarget()));
        assertEquals(files, AppReference.fromPersistentKey(files.persistentKey()));
        assertEquals(settings, AppReference.fromPersistentKey(settings.persistentKey()));
        assertNull(PERSONAL.reference(AppLaunchTarget.packageDefault(BuildConfig.APPLICATION_ID)));
        for (final String invalid : new String[]{"example.app", "-1|example.app",
                "05|example.app", "5|" + BuildConfig.APPLICATION_ID,
                "5|example.app|" + files.launchTarget().activityClassName,
                files.persistentKey() + "|extra"}) {
            assertThrows(invalid, IllegalArgumentException.class,
                    () -> AppReference.fromPersistentKey(invalid));
        }
    }

    @Test public void unboundStoredEntriesNeverAcquireCurrentProfile() throws Exception {
        final DesktopStateStore.State state = DesktopStateStore.decode(
                "{\"format\":" + DesktopStateStore.FORMAT
                + ",\"taskbar\":[\"example.app\"],"
                + "\"appWindows\":{\"example.app\":{\"mode\":\"fullscreen\"}},"
                + "\"appPresentations\":{\"example.app\":125}}");
        assertTrue(state.taskbarApps.isEmpty());
        assertTrue(state.appWindows.isEmpty());
        assertTrue(state.appPresentations.isEmpty());
        assertTrue(DesktopPreferences.decodeRecentApps("[\"example.app\"]").isEmpty());
    }

    @Test public void catalogSelectionCannotDiscardProfileOrBuiltInIdentity() {
        final AppItem personal = item(PERSONAL, TARGET);
        final AppItem work = item(WORK, TARGET);
        final AppItem files = item(PERSONAL, BuiltInDesktopAppCatalog.filesTarget());
        final AppItem settings = item(PERSONAL, BuiltInDesktopAppCatalog.settingsTarget());
        final List<AppItem> catalog = List.of(personal, work, files, settings);
        assertSame(work, LauncherAppRepository.find(catalog, WORK_APP));
        assertSame(personal, LauncherAppRepository.findApplication(catalog, PERSONAL_APP.application));
        assertSame(settings, LauncherAppRepository.find(catalog, settings.reference));
        assertNull(LauncherAppRepository.find(catalog, WORK.reference(files.launchTarget)));
        assertNull(PERSONAL.applicationForUser(-1, TARGET.packageName));
        assertNull(PERSONAL.applicationForUser(WORK.userId, TARGET.packageName));
    }

    @Test public void persistentCollectionsRequireTypedKeys() throws Exception {
        assertKeyType("taskbarApps", AppReference.class);
        assertKeyType("appWindows", AppReference.class);
        assertKeyType("appPresentations", AppIdentity.class);
        for (final Class<?> owner : List.of(AppWindowStateStore.class,
                AppPresentationProfileStore.class, DesktopPreferences.class)) {
            for (final var method : owner.getDeclaredMethods()) {
                if (method.getName().startsWith("load") || method.getName().startsWith("remember")
                        || method.getName().equals("setScale") || method.getName().equals("reset")) {
                    for (final Class<?> parameter : method.getParameterTypes()) {
                        assertNotEquals(method.toString(), String.class, parameter);
                    }
                }
            }
        }
    }

    private static void assertKeyType(String field, Class<?> expected) throws Exception {
        final ParameterizedType type = (ParameterizedType)
                DesktopStateStore.State.class.getDeclaredField(field).getGenericType();
        assertEquals(expected, type.getActualTypeArguments()[0]);
    }

    private static AppItem item(AppProfile profile, AppLaunchTarget target) {
        return new AppItem(profile, target.packageName, target.packageName, false,
                AppItem.FULLSCREEN_REASON_NONE, null, target);
    }
}

