package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Process;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Temporarily owns Android's HOME role for one desktop session. */
final class DesktopHomeRoleLease {
    private static final String HOME_ROLE = "android.app.role.HOME";
    private static final String MAGICDESK_PACKAGE = BuildConfig.APPLICATION_ID;
    private static final int DONT_KILL_APP = 1;
    private static final int HOME_ACTIVITY_FLAGS =
            Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED;
    private static final int PER_USER_RANGE = 100_000;
    private static final Object LOCK = new Object();
    private static final Storage DEFAULT_STORAGE =
            new PreferencesStorage();
    private static final Backend DEFAULT_BACKEND = new ShellBackend();

    private static Storage sStorage = DEFAULT_STORAGE;
    private static Backend sBackend = DEFAULT_BACKEND;
    private static volatile boolean sPhoneOverviewRoutingActive;

    private DesktopHomeRoleLease() {
    }

    enum Phase {
        PREPARED,
        ACTIVE,
        RELEASING
    }

    static final class State {
        final int userId;
        final AndroidHomeSelection previousHome;
        private final DesktopDisplayTarget target;
        final DesktopSessionPolicy policy;
        final DesktopCompatibilityPolicy compatibility;
        final Phase phase;

        State(
                final int userId,
                final AndroidHomeSelection previousHome,
                final DesktopDisplayTarget target,
                final DesktopSessionPolicy policy,
                final DesktopCompatibilityPolicy compatibility,
                final Phase phase) {
            if (userId < 0
                    || target == null
                    || previousHome == null
                    || policy == null
                    || compatibility == null
                    || phase == null) {
                throw new IllegalArgumentException(
                        "complete HOME lease state is required");
            }
            this.userId = userId;
            this.previousHome = previousHome;
            this.target = target;
            this.policy = policy;
            this.compatibility = compatibility;
            this.phase = phase;
        }

        State withPhase(final Phase newPhase) {
            return new State(
                    userId,
                    previousHome,
                    target(),
                    policy,
                    compatibility,
                    newPhase);
        }

        boolean matches(final DesktopDisplayTarget target) {
            return this.target.sameBinding(target);
        }

        DesktopDisplayTarget target() {
            return target;
        }
    }

    static final class AcquireResult {
        final boolean created;
        final State state;

        AcquireResult(final boolean created, final State state) {
            this.created = created;
            this.state = state;
        }
    }

    static final class RestoredHomePresentation {
        final int userId;

        private RestoredHomePresentation(final int userId) {
            this.userId = userId;
        }
    }

    interface Storage {
        State read();

        void write(State state) throws IOException;

        void clear() throws IOException;
    }

    interface Backend {
        int currentUserId();

        String getHomePackage(int userId) throws IOException;

        AndroidHomeSelection resolveHomeSelection(
                int userId,
                String packageName) throws IOException;

        void selectHomeSurface(DesktopHomeSurfaceRouter.Selection selection)
                throws IOException;

        void disableHomeSurfaces() throws IOException;

        void setHomePackage(int userId, String packageName) throws IOException;

        void clearHomePackage(int userId, String packageName)
                throws IOException;

        void presentHome(int userId, String packageName) throws IOException;
    }

    /** Persists recovery state and enables components without claiming HOME. */
    static AcquireResult prepare(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy,
            final DesktopCompatibilityPolicy compatibility) throws IOException {
        if (target == null || target.workspaceDisplayId < 0) {
            throw new IOException("desktop HOME target is invalid");
        }
        synchronized (LOCK) {
            final State existing = sStorage.read();
            if (existing != null) {
                if (!existing.matches(target)) {
                    throw new IOException(
                            "HOME is already leased to "
                                    + existing.target().output.kind
                                    + " display=" + existing.target().workspaceDisplayId);
                }
                if (existing.policy != policy) {
                    throw new IOException("HOME lease policy mismatch: leased="
                            + existing.policy + " requested=" + policy);
                }
                if (existing.phase == Phase.RELEASING) {
                    throw new IOException("HOME lease is releasing for "
                            + existing.target().output.kind + " display=" + existing.target().workspaceDisplayId);
                }
                final String holder = sBackend.getHomePackage(existing.userId);
                if (MAGICDESK_PACKAGE.equals(holder)) {
                    sBackend.selectHomeSurface(surfacesFor(existing));
                    return new AcquireResult(false, existing);
                }
                if (existing.phase == Phase.PREPARED
                        && existing.previousHome.packageName.equals(holder)) {
                    try {
                        sBackend.selectHomeSurface(surfacesFor(existing));
                        return new AcquireResult(true, existing);
                    } catch (IOException error) {
                        restorePreparedLease(existing, error);
                        throw error;
                    }
                }
                throw new IOException(
                        "stored HOME lease disagrees with role holder " + holder);
            }

            final int userId = sBackend.currentUserId();
            final String previousPackage = sBackend.getHomePackage(userId);
            if (MAGICDESK_PACKAGE.equals(previousPackage)) {
                throw new IOException(
                        "MagicDesk already owns HOME without a recoverable lease");
            }
            if (!previousPackage.isEmpty()
                    && !PackageNameValidator.isSafe(previousPackage)) {
                throw new IOException(
                        "current HOME package is unavailable: " + previousPackage);
            }
            final AndroidHomeSelection previousHome = resolvePreviousHome(
                    userId, previousPackage);
            final State prepared = new State(
                    userId,
                    previousHome,
                    target,
                    policy,
                    compatibility,
                    Phase.PREPARED);
            sStorage.write(prepared);
            try {
                sBackend.selectHomeSurface(surfacesFor(prepared));
                return new AcquireResult(true, prepared);
            } catch (IOException error) {
                restorePreparedLease(prepared, error);
                throw error;
            }
        }
    }

    static boolean release(final DesktopDisplayTarget target)
            throws IOException {
        synchronized (LOCK) {
            final State state = requireTarget(target);
            if (state == null) {
                return false;
            }
            restoreOrAbandon(state);
            return true;
        }
    }

    /** Hands HOME back without finishing Activity instances during task parking. */
    static void releaseForSessionClose(
            final DesktopDisplayTarget target) throws IOException {
        synchronized (LOCK) {
            final State state = requireTarget(target);
            if (state != null) {
                beginRelease(state);
                restoreRole(state);
            }
        }
    }

    /** Called by the close owner after task, host and owned-display teardown. */
    static RestoredHomePresentation finishSessionClose(
            final DesktopDisplayTarget target) throws IOException {
        synchronized (LOCK) {
            final State state = requireTarget(target);
            if (state == null) {
                return null;
            }
            if (state.phase != Phase.RELEASING) {
                throw new IOException("HOME release has not started");
            }
            return finishRelease(state);
        }
    }

    static void presentRestoredHome(
            final RestoredHomePresentation presentation) throws IOException {
        if (presentation == null) {
            return;
        }
        synchronized (LOCK) {
            sBackend.presentHome(
                    presentation.userId,
                    sBackend.getHomePackage(presentation.userId));
        }
    }

    private static State requireTarget(
            final DesktopDisplayTarget target) throws IOException {
        final State state = sStorage.read();
        if (state == null) {
            return null;
        }
        if (!state.matches(target)) {
            throw new IOException("HOME lease target mismatch: leased="
                    + state.target().output.kind + "/" + state.target().workspaceDisplayId
                    + " requested=" + (target == null
                            ? "none"
                            : target.output.kind + "/" + target.workspaceDisplayId));
        }
        return state;
    }

    static void releaseAfterFailedStart(final AcquireResult acquisition)
            throws IOException {
        if (acquisition == null || !acquisition.created) {
            return;
        }
        release(acquisition.state.target());
    }

    static boolean releaseAfterSessionLoss(final int displayId)
            throws IOException {
        synchronized (LOCK) {
            final State state = sStorage.read();
            if (state == null || state.target().workspaceDisplayId != displayId) {
                return false;
            }
            restoreOrAbandon(state);
            return true;
        }
    }

    static boolean reconcile(final boolean sessionAlive)
            throws IOException {
        synchronized (LOCK) {
            final State state = sStorage.read();
            if (state == null) {
                sPhoneOverviewRoutingActive = false;
                sBackend.disableHomeSurfaces();
                return false;
            }
            final String holder = sBackend.getHomePackage(state.userId);
            if (state.phase == Phase.ACTIVE
                    && sessionAlive
                    && MAGICDESK_PACKAGE.equals(holder)) {
                sPhoneOverviewRoutingActive = true;
                sBackend.selectHomeSurface(surfacesFor(state));
                return false;
            }
            restoreOrAbandon(state);
            return true;
        }
    }

    static boolean discardForStartupRelinquish() throws IOException {
        synchronized (LOCK) {
            sPhoneOverviewRoutingActive = false;
            final State state = sStorage.read();
            if (state == null) {
                return false;
            }
            sStorage.clear();
            return true;
        }
    }

    static State snapshot() {
        synchronized (LOCK) {
            return sStorage.read();
        }
    }

    static boolean isActiveForDisplay(final int displayId) {
        return isForDisplay(displayId, Phase.ACTIVE);
    }

    static boolean isReleasingForDisplay(final int displayId) {
        return isForDisplay(displayId, Phase.RELEASING);
    }

    private static boolean isForDisplay(final int displayId, final Phase phase) {
        synchronized (LOCK) {
            final State state = sStorage.read();
            return state != null
                    && state.phase == phase
                    && state.target().workspaceDisplayId == displayId;
        }
    }

    static boolean isActiveForSurface(
            final DesktopHomeSurfaceRouter.Surface surface) {
        return isForSurface(surface, Phase.ACTIVE);
    }

    static boolean isReleasingForSurface(
            final DesktopHomeSurfaceRouter.Surface surface) {
        return isForSurface(surface, Phase.RELEASING);
    }

    private static boolean isForSurface(
            final DesktopHomeSurfaceRouter.Surface surface,
            final Phase phase) {
        synchronized (LOCK) {
            final State state = sStorage.read();
            return state != null
                    && state.phase == phase
                    && surfacesFor(state).primary == surface;
        }
    }

    static boolean isPhoneOverviewRoutingActive() {
        return sPhoneOverviewRoutingActive;
    }

    static void useForTests(
            final Storage storage,
            final Backend backend) {
        synchronized (LOCK) {
            sPhoneOverviewRoutingActive = false;
            sStorage = storage == null ? DEFAULT_STORAGE : storage;
            sBackend = backend == null ? DEFAULT_BACKEND : backend;
        }
    }

    private static void claim(final State state) throws IOException {
        sBackend.setHomePackage(state.userId, MAGICDESK_PACKAGE);
        requireHolder(state.userId, MAGICDESK_PACKAGE);
    }

    /** Claims the role only after component and display preparation. */
    static AcquireResult activate(final AcquireResult preparation)
            throws IOException {
        synchronized (LOCK) {
            final State prepared = requireTarget(preparation.state.target());
            if (prepared == null || prepared.phase == Phase.RELEASING
                    || prepared.policy != preparation.state.policy) {
                throw new IOException("HOME preparation is no longer current");
            }
            try {
                final String holder = sBackend.getHomePackage(prepared.userId);
                if (!MAGICDESK_PACKAGE.equals(holder)) {
                    if (prepared.phase != Phase.PREPARED
                            || !prepared.previousHome.packageName.equals(holder)) {
                        throw new IOException("HOME changed during preparation");
                    }
                    claim(prepared);
                }
                final State active = prepared.withPhase(Phase.ACTIVE);
                sStorage.write(active);
                sPhoneOverviewRoutingActive = true;
                if (shouldPresentMagicDeskHome(active)) {
                    sBackend.presentHome(active.userId, MAGICDESK_PACKAGE);
                }
                return new AcquireResult(preparation.created, active);
            } catch (IOException error) {
                if (preparation.created) {
                    restorePreparedLease(prepared, error);
                }
                throw error;
            }
        }
    }

    private static boolean shouldPresentMagicDeskHome(final State state) {
        return state.target().isDefaultWorkspace()
                || state.policy != DesktopSessionPolicy.ISOLATED_SELF_TEST;
    }

    private static DesktopHomeSurfaceRouter.Selection surfacesFor(
            final State state) {
        return DesktopHomeSurfaceRouter.forWorkspaces(java.util.Collections.singletonList(state.target()));
    }

    private static void restoreOrAbandon(final State state)
            throws IOException {
        beginRelease(state);
        presentRestoredHome(finishRelease(state));
    }

    private static void beginRelease(final State state) throws IOException {
        sPhoneOverviewRoutingActive = false;
        if (state.phase != Phase.RELEASING) {
            sStorage.write(state.withPhase(Phase.RELEASING));
        }
    }

    private static void restoreRole(final State state) throws IOException {
        final String holder = sBackend.getHomePackage(state.userId);
        if (MAGICDESK_PACKAGE.equals(holder)
                || (holder.isEmpty() && !state.previousHome.packageName.isEmpty())) {
            restorePreviousHolder(state);
        }
    }

    private static RestoredHomePresentation finishRelease(final State state)
            throws IOException {
        IOException restoreError = null;
        try {
            restoreRole(state);
        } catch (IOException error) {
            restoreError = error;
        }
        try {
            // Disabling a live HOME Activity starts Android CLOSE transitions.
            // Normal Close reaches here only after its workspace teardown.
            // Recovery still disables components when role restoration fails.
            sBackend.disableHomeSurfaces();
        } catch (IOException error) {
            if (restoreError == null) {
                restoreError = error;
            } else {
                restoreError.addSuppressed(error);
            }
        }
        if (restoreError != null) {
            throw restoreError;
        }
        sStorage.clear();
        return new RestoredHomePresentation(state.userId);
    }

    private static void restorePreparedLease(
            final State state,
            final IOException acquisitionError) {
        sPhoneOverviewRoutingActive = false;
        try {
            beginRelease(state);
            finishRelease(state);
        } catch (IOException restoreError) {
            acquisitionError.addSuppressed(restoreError);
        }
    }

    private static void requireHolder(
            final int userId,
            final String expectedPackage) throws IOException {
        final String actualPackage = sBackend.getHomePackage(userId);
        if (!expectedPackage.equals(actualPackage)) {
            throw new IOException(
                    "HOME role verification failed: expected="
                            + expectedPackage + " actual=" + actualPackage);
        }
    }

    private static void restorePreviousHolder(final State state)
            throws IOException {
        if (state.previousHome.packageName.isEmpty()) {
            sBackend.clearHomePackage(state.userId, MAGICDESK_PACKAGE);
        } else {
            sBackend.setHomePackage(
                    state.userId, state.previousHome.packageName);
        }
        requireHolder(state.userId, state.previousHome.packageName);
    }

    private static AndroidHomeSelection resolvePreviousHome(
            final int userId,
            final String packageName) {
        if (packageName.isEmpty()) {
            return AndroidHomeSelection.none();
        }
        try {
            final AndroidHomeSelection selection =
                    sBackend.resolveHomeSelection(userId, packageName);
            return selection != null
                            && packageName.equals(selection.packageName)
                    ? selection : AndroidHomeSelection.unresolved(packageName);
        } catch (IOException | RuntimeException error) {
            // Static metadata is optional. Role ownership and recovery must
            // not depend on whether PackageManager can describe the launcher.
            return AndroidHomeSelection.unresolved(packageName);
        }
    }

    private static final class ShellBackend implements Backend {
        @Override
        public int currentUserId() {
            return Process.myUid() / PER_USER_RANGE;
        }

        @Override
        public String getHomePackage(final int userId) throws IOException {
            final String output = ShellAccess.run(
                    "/system/bin/cmd role get-role-holders --user "
                            + userId + " " + HOME_ROLE);
            final List<String> packages = new ArrayList<>();
            for (final String line : output.split("\\r?\\n")) {
                final String packageName = line.trim();
                if (!packageName.isEmpty()
                        && PackageNameValidator.isSafe(packageName)) {
                    packages.add(packageName);
                }
            }
            if (packages.size() > 1) {
                throw new IOException(
                        "expected at most one HOME role holder, found "
                                + packages.size());
            }
            return packages.isEmpty() ? "" : packages.get(0);
        }

        @Override
        public AndroidHomeSelection resolveHomeSelection(
                final int userId,
                final String packageName) throws IOException {
            if (userId != currentUserId()
                    || !PackageNameValidator.isSafe(packageName)) {
                throw new IOException("invalid HOME selection request");
            }
            final Intent intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addCategory(Intent.CATEGORY_DEFAULT)
                    .setPackage(packageName);
            return AndroidHomeSelection.fromResolution(
                    packageName,
                    ShellAccess.resolveActivity(intent));
        }

        @Override
        public void selectHomeSurface(
                final DesktopHomeSurfaceRouter.Selection selection)
                throws IOException {
            DesktopHomeSurfaceRouter.select(selection);
        }

        @Override
        public void disableHomeSurfaces() throws IOException {
            DesktopHomeSurfaceRouter.disableHomeSurfaces();
        }

        @Override
        public void setHomePackage(
                final int userId,
                final String packageName) throws IOException {
            if (!PackageNameValidator.isSafe(packageName)) {
                throw new IOException("invalid HOME package " + packageName);
            }
            ShellAccess.run(
                    "/system/bin/cmd role add-role-holder --user "
                            + userId + " " + HOME_ROLE + " "
                            + ShellCommandLine.quote(packageName) + " "
                            + DONT_KILL_APP);
        }

        @Override
        public void clearHomePackage(
                final int userId,
                final String packageName) throws IOException {
            if (!PackageNameValidator.isSafe(packageName)) {
                throw new IOException("invalid HOME package " + packageName);
            }
            ShellAccess.run(
                    "/system/bin/cmd role remove-role-holder --user "
                            + userId + " " + HOME_ROLE + " "
                            + ShellCommandLine.quote(packageName) + " "
                            + DONT_KILL_APP);
        }

        @Override
        public void presentHome(
                final int userId,
                final String packageName) throws IOException {
            final String packageArgument = packageName == null
                    || packageName.isEmpty()
                    ? ""
                    : " -p " + ShellCommandLine.quote(packageName);
            ShellAccess.run(
                    "/system/bin/am start --user " + userId
                            + " --display 0"
                            + " -f 0x"
                            + Integer.toHexString(HOME_ACTIVITY_FLAGS)
                            + " -a android.intent.action.MAIN"
                            + " -c android.intent.category.HOME"
                            + packageArgument);
        }
    }

    private static final class PreferencesStorage implements Storage {
        private static final int STORAGE_FORMAT = 2;
        private static final String PREFERENCES =
                "magicdesk_desktop_home_lease";
        private static final String FORMAT = "format";
        private static final String USER_ID = "user_id";
        private static final String PREVIOUS_PACKAGE = "previous_package";
        private static final String PREVIOUS_COMPONENT = "previous_component";
        private static final String PREVIOUS_VERSION_CODE =
                "previous_version_code";
        private static final String PREVIOUS_AVAILABILITY =
                "previous_availability";
        private static final String TARGET_KIND = "target_kind";
        private static final String DISPLAY_ID = "display_id";
        private static final String OUTPUT_DISPLAY_ID = "output_display_id";
        private static final String PROFILE_KEY = "profile_key";
        private static final String ACTIVATION_SOURCE = "activation_source";
        private static final String SESSION_POLICY = "session_policy";
        private static final String COMPATIBILITY = "compatibility";
        private static final String PHASE = "phase";

        @Override
        public State read() {
            final SharedPreferences preferences = preferences();
            if (!hasCurrentFormat(preferences)) {
                return null;
            }
            try {
                final AndroidHomeSelection previousHome =
                        AndroidHomeSelection.fromPersisted(
                                requiredString(
                                        preferences, PREVIOUS_PACKAGE),
                                requiredString(
                                        preferences, PREVIOUS_COMPONENT),
                                preferences.getLong(
                                        PREVIOUS_VERSION_CODE, -1),
                                requiredString(
                                        preferences,
                                        PREVIOUS_AVAILABILITY));
                return new State(
                        preferences.getInt(USER_ID, -1),
                        previousHome,
                        DesktopDisplayTarget.restore(
                                DesktopDisplayOutput.Kind.valueOf(
                                        requiredString(
                                                preferences, TARGET_KIND)),
                                preferences.getInt(DISPLAY_ID, -1),
                                preferences.getInt(
                                        OUTPUT_DISPLAY_ID, -1),
                                requiredString(preferences, PROFILE_KEY),
                                DesktopDisplayOutput.ActivationSource.valueOf(
                                        requiredString(
                                                preferences,
                                                ACTIVATION_SOURCE))),
                        DesktopSessionPolicy.valueOf(
                                requiredString(
                                        preferences, SESSION_POLICY)),
                        DesktopCompatibilityPolicy.fromBits(
                                preferences.getInt(COMPATIBILITY, 0)),
                        Phase.valueOf(requiredString(preferences, PHASE)));
            } catch (ClassCastException | IllegalArgumentException error) {
                return null;
            }
        }

        @Override
        @SuppressLint("ApplySharedPref")
        public void write(final State state) throws IOException {
            if (state == null
                    || !preferences().edit()
                            .putInt(FORMAT, STORAGE_FORMAT)
                            .putInt(USER_ID, state.userId)
                            .putString(
                                    PREVIOUS_PACKAGE,
                                    state.previousHome.packageName)
                            .putString(
                                    PREVIOUS_COMPONENT,
                                    state.previousHome.componentName)
                            .putLong(
                                    PREVIOUS_VERSION_CODE,
                                    state.previousHome.packageVersionCode)
                            .putString(
                                    PREVIOUS_AVAILABILITY,
                                    state.previousHome.availability.name())
                            .putString(TARGET_KIND, state.target().output.kind.name())
                            .putInt(DISPLAY_ID, state.target().workspaceDisplayId)
                            .putInt(
                                    OUTPUT_DISPLAY_ID,
                                    state.target().output.displayId)
                            .putString(PROFILE_KEY, state.target().output.profileKey)
                            .putString(
                                    ACTIVATION_SOURCE,
                                    state.target().output.activationSource.name())
                            .putString(SESSION_POLICY, state.policy.name())
                            .putInt(COMPATIBILITY, state.compatibility.bits())
                            .putString(PHASE, state.phase.name())
                            .commit()) {
                throw new IOException("could not persist desktop HOME lease");
            }
        }

        @Override
        @SuppressLint("ApplySharedPref")
        public void clear() throws IOException {
            if (!preferences().edit().clear().commit()) {
                throw new IOException("could not clear desktop HOME lease");
            }
        }

        private static boolean hasCurrentFormat(
                final SharedPreferences preferences) {
            try {
                return preferences.getInt(FORMAT, -1) == STORAGE_FORMAT
                        && preferences.contains(USER_ID)
                        && preferences.contains(PREVIOUS_PACKAGE)
                        && preferences.contains(PREVIOUS_COMPONENT)
                        && preferences.contains(PREVIOUS_VERSION_CODE)
                        && preferences.contains(PREVIOUS_AVAILABILITY)
                        && preferences.contains(TARGET_KIND)
                        && preferences.contains(DISPLAY_ID)
                        && preferences.contains(OUTPUT_DISPLAY_ID)
                        && preferences.contains(PROFILE_KEY)
                        && preferences.contains(ACTIVATION_SOURCE)
                        && preferences.contains(SESSION_POLICY)
                        && preferences.contains(COMPATIBILITY)
                        && preferences.contains(PHASE);
            } catch (ClassCastException error) {
                return false;
            }
        }

        private static String requiredString(
                final SharedPreferences preferences,
                final String key) {
            final String value = preferences.getString(key, null);
            if (value == null) {
                throw new IllegalArgumentException(
                        "missing HOME lease field " + key);
            }
            return value;
        }

        private static SharedPreferences preferences() {
            final Context context = MagicDeskApplication.applicationContext();
            return context.getSharedPreferences(
                    PREFERENCES, Context.MODE_PRIVATE);
        }
    }
}
