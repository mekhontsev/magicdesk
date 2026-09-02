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
        final String previousPackage;
        final DesktopDisplayTarget.Kind targetKind;
        final int displayId;
        final int profileDisplayId;
        final String profileKey;
        final DesktopDisplayTarget.ActivationSource activationSource;
        final DesktopSessionPolicy policy;
        final Phase phase;

        State(
                final int userId,
                final String previousPackage,
                final DesktopDisplayTarget target,
                final DesktopSessionPolicy policy,
                final Phase phase) {
            if (target == null) {
                throw new IllegalArgumentException("HOME lease target is required");
            }
            this.userId = userId;
            this.previousPackage = previousPackage;
            this.targetKind = target.kind;
            this.displayId = target.displayId;
            this.profileDisplayId = target.profileDisplayId;
            this.profileKey = target.profileKey;
            this.activationSource = target.activationSource;
            this.policy = policy == null
                    ? DesktopSessionPolicy.USER : policy;
            this.phase = phase;
        }

        State withPhase(final Phase newPhase) {
            return new State(
                    userId,
                    previousPackage,
                    target(),
                    policy,
                    newPhase);
        }

        boolean matches(final DesktopDisplayTarget target) {
            return target != null
                    && targetKind == target.kind
                    && displayId == target.displayId;
        }

        DesktopDisplayTarget target() {
            return DesktopDisplayTarget.restore(
                    targetKind,
                    displayId,
                    profileDisplayId,
                    profileKey,
                    activationSource);
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

    interface Storage {
        State read();

        void write(State state) throws IOException;

        void clear() throws IOException;
    }

    interface Backend {
        int currentUserId();

        String getHomePackage(int userId) throws IOException;

        void selectHomeSurface(DesktopHomeSurfaceRouter.Surface surface)
                throws IOException;

        void restoreHomeSurface() throws IOException;

        void setHomePackage(int userId, String packageName) throws IOException;

        void presentMagicDeskHome(int userId) throws IOException;
    }

    static AcquireResult acquire(final DesktopDisplayTarget target)
            throws IOException {
        return acquire(target, DesktopSessionPolicy.USER);
    }

    static AcquireResult acquire(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) throws IOException {
        if (target == null || target.displayId < 0) {
            throw new IOException("desktop HOME target is invalid");
        }
        synchronized (LOCK) {
            final State existing = sStorage.read();
            if (existing != null) {
                if (!existing.matches(target)) {
                    throw new IOException(
                            "HOME is already leased to "
                                    + existing.targetKind
                                    + " display=" + existing.displayId);
                }
                final String holder = sBackend.getHomePackage(existing.userId);
                if (MAGICDESK_PACKAGE.equals(holder)) {
                    sBackend.selectHomeSurface(surfaceFor(existing));
                    final State active = existing.withPhase(Phase.ACTIVE);
                    sStorage.write(active);
                    sPhoneOverviewRoutingActive = true;
                    if (shouldPresentHome(active)) {
                        sBackend.presentMagicDeskHome(active.userId);
                    }
                    return new AcquireResult(false, active);
                }
                if (existing.phase == Phase.PREPARED
                        && existing.previousPackage.equals(holder)) {
                    try {
                        return activatePrepared(existing);
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
            if (!PackageNameValidator.isSafe(previousPackage)) {
                throw new IOException(
                        "current HOME package is unavailable: " + previousPackage);
            }
            final State prepared = new State(
                    userId,
                    previousPackage,
                    target,
                    policy,
                    Phase.PREPARED);
            sStorage.write(prepared);
            try {
                return activatePrepared(prepared);
            } catch (IOException error) {
                restorePreparedLease(prepared, error);
                throw error;
            }
        }
    }

    static boolean release(final DesktopDisplayTarget target)
            throws IOException {
        synchronized (LOCK) {
            final State state = sStorage.read();
            if (state == null) {
                return false;
            }
            if (!state.matches(target)) {
                throw new IOException("HOME lease target mismatch: leased="
                        + state.targetKind + "/" + state.displayId
                        + " requested=" + (target == null
                                ? "none"
                                : target.kind + "/" + target.displayId));
            }
            final State releasing = state.withPhase(Phase.RELEASING);
            sStorage.write(releasing);
            sPhoneOverviewRoutingActive = false;
            final String holder = sBackend.getHomePackage(state.userId);
            if (MAGICDESK_PACKAGE.equals(holder)) {
                sBackend.setHomePackage(
                        state.userId, state.previousPackage);
                requireHolder(state.userId, state.previousPackage);
            }
            sBackend.restoreHomeSurface();
            sStorage.clear();
            return true;
        }
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
            if (state == null || state.displayId != displayId) {
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
                sBackend.restoreHomeSurface();
                return false;
            }
            final String holder = sBackend.getHomePackage(state.userId);
            if (state.phase == Phase.ACTIVE
                    && sessionAlive
                    && MAGICDESK_PACKAGE.equals(holder)) {
                sPhoneOverviewRoutingActive = true;
                sBackend.selectHomeSurface(surfaceFor(state));
                return false;
            }
            restoreOrAbandon(state, holder);
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
        synchronized (LOCK) {
            final State state = sStorage.read();
            return state != null
                    && state.phase == Phase.ACTIVE
                    && state.displayId == displayId;
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

    private static AcquireResult activatePrepared(final State prepared)
            throws IOException {
        final DesktopHomeSurfaceRouter.Surface surface = surfaceFor(prepared);
        sBackend.selectHomeSurface(surface);
        claim(prepared);
        final State active = prepared.withPhase(Phase.ACTIVE);
        sStorage.write(active);
        sPhoneOverviewRoutingActive = true;
        if (shouldPresentHome(prepared)) {
            sBackend.presentMagicDeskHome(prepared.userId);
        }
        return new AcquireResult(true, active);
    }

    private static boolean shouldPresentHome(final State state) {
        return state.targetKind == DesktopDisplayTarget.Kind.PHONE
                || state.policy != DesktopSessionPolicy.ISOLATED_SELF_TEST;
    }

    private static DesktopHomeSurfaceRouter.Surface surfaceFor(
            final State state) {
        return DesktopHomeSurfaceRouter.forTarget(state.targetKind);
    }

    private static void restoreOrAbandon(final State state)
            throws IOException {
        restoreOrAbandon(
                state, sBackend.getHomePackage(state.userId));
    }

    private static void restoreOrAbandon(
            final State state,
            final String holder) throws IOException {
        sPhoneOverviewRoutingActive = false;
        if (state.phase != Phase.RELEASING) {
            sStorage.write(state.withPhase(Phase.RELEASING));
        }
        if (MAGICDESK_PACKAGE.equals(holder)) {
            sBackend.setHomePackage(state.userId, state.previousPackage);
            requireHolder(state.userId, state.previousPackage);
        }
        sBackend.restoreHomeSurface();
        sStorage.clear();
    }

    private static void restorePreparedLease(
            final State state,
            final IOException acquisitionError) {
        sPhoneOverviewRoutingActive = false;
        try {
            final String holder = sBackend.getHomePackage(state.userId);
            if (!state.previousPackage.equals(holder)) {
                sBackend.setHomePackage(
                        state.userId, state.previousPackage);
                requireHolder(state.userId, state.previousPackage);
            }
            sBackend.restoreHomeSurface();
            sStorage.clear();
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
            if (packages.size() != 1) {
                throw new IOException(
                        "expected one HOME role holder, found "
                                + packages.size());
            }
            return packages.get(0);
        }

        @Override
        public void selectHomeSurface(
                final DesktopHomeSurfaceRouter.Surface surface)
                throws IOException {
            DesktopHomeSurfaceRouter.select(surface);
        }

        @Override
        public void restoreHomeSurface() throws IOException {
            DesktopHomeSurfaceRouter.restoreDefault();
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
        public void presentMagicDeskHome(final int userId) throws IOException {
            ShellAccess.run(
                    "/system/bin/am start --user " + userId
                            + " -f 0x"
                            + Integer.toHexString(HOME_ACTIVITY_FLAGS)
                            + " -a android.intent.action.MAIN"
                            + " -c android.intent.category.HOME"
                            + " -p " + MAGICDESK_PACKAGE);
        }
    }

    private static final class PreferencesStorage implements Storage {
        private static final String PREFERENCES =
                "magicdesk_desktop_home_lease";
        private static final String USER_ID = "user_id";
        private static final String PREVIOUS_PACKAGE = "previous_package";
        private static final String TARGET_KIND = "target_kind";
        private static final String DISPLAY_ID = "display_id";
        private static final String PROFILE_DISPLAY_ID = "profile_display_id";
        private static final String PROFILE_KEY = "profile_key";
        private static final String ACTIVATION_SOURCE = "activation_source";
        private static final String SESSION_POLICY = "session_policy";
        private static final String PHASE = "phase";

        @Override
        public State read() {
            final SharedPreferences preferences = preferences();
            final String previousPackage = preferences.getString(
                    PREVIOUS_PACKAGE, "");
            final String targetKind = preferences.getString(TARGET_KIND, "");
            final String phase = preferences.getString(PHASE, "");
            if (!PackageNameValidator.isSafe(previousPackage)
                    || targetKind == null || targetKind.isEmpty()
                    || phase == null || phase.isEmpty()) {
                return null;
            }
            try {
                return new State(
                        preferences.getInt(USER_ID, 0),
                        previousPackage,
                        DesktopDisplayTarget.restore(
                                DesktopDisplayTarget.Kind.valueOf(targetKind),
                                preferences.getInt(DISPLAY_ID, -1),
                                preferences.getInt(
                                        PROFILE_DISPLAY_ID,
                                        preferences.getInt(DISPLAY_ID, -1)),
                                preferences.getString(PROFILE_KEY, ""),
                                DesktopDisplayTarget.ActivationSource.valueOf(
                                        preferences.getString(
                                                ACTIVATION_SOURCE,
                                                DesktopDisplayTarget
                                                        .ActivationSource
                                                        .UNKNOWN.name()))),
                        DesktopSessionPolicy.parse(
                                preferences.getString(
                                        SESSION_POLICY,
                                        DesktopSessionPolicy.USER.name())),
                        Phase.valueOf(phase));
            } catch (IllegalArgumentException error) {
                return null;
            }
        }

        @Override
        @SuppressLint("ApplySharedPref")
        public void write(final State state) throws IOException {
            if (state == null
                    || !preferences().edit()
                            .putInt(USER_ID, state.userId)
                            .putString(
                                    PREVIOUS_PACKAGE,
                                    state.previousPackage)
                            .putString(TARGET_KIND, state.targetKind.name())
                            .putInt(DISPLAY_ID, state.displayId)
                            .putInt(
                                    PROFILE_DISPLAY_ID,
                                    state.profileDisplayId)
                            .putString(PROFILE_KEY, state.profileKey)
                            .putString(
                                    ACTIVATION_SOURCE,
                                    state.activationSource.name())
                            .putString(SESSION_POLICY, state.policy.name())
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

        private static SharedPreferences preferences() {
            final Context context = MagicDeskApplication.applicationContext();
            return context.getSharedPreferences(
                    PREFERENCES, Context.MODE_PRIVATE);
        }
    }
}
