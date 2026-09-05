package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns the asynchronous, shell-backed part of Start search. */
final class StartSearchController implements AutoCloseable {
    enum Kind {
        APP,
        DESKTOP_APPLICATION,
        BUILT_IN,
        ACTION,
        FILE
    }

    enum Action {
        SHOW_DESKTOP,
        SCREENSHOT,
        SCREEN_RECORDING
    }

    static final class Result {
        final Kind kind;
        final String label;
        final String detail;
        final AppItem app;
        final DesktopApplicationRepository.Entry desktopApplication;
        final BuiltInDesktopAppCatalog.Entry builtIn;
        final Action action;
        final ShellFileInfo file;

        private Result(
                final Kind kind,
                final String label,
                final String detail,
                final AppItem app,
                final DesktopApplicationRepository.Entry desktopApplication,
                final BuiltInDesktopAppCatalog.Entry builtIn,
                final Action action,
                final ShellFileInfo file) {
            this.kind = kind;
            this.label = label;
            this.detail = detail;
            this.app = app;
            this.desktopApplication = desktopApplication;
            this.builtIn = builtIn;
            this.action = action;
            this.file = file;
        }

        static Result app(final AppItem app) {
            return new Result(
                    Kind.APP,
                    app.label,
                    app.packageName,
                    app,
                    null,
                    null,
                    null,
                    null);
        }

        static Result desktopApplication(
                final DesktopApplicationRepository.Entry application) {
            final DesktopApplicationShortcut shortcut = application.shortcut;
            return new Result(
                    Kind.DESKTOP_APPLICATION,
                    shortcut.name,
                    shortcut.execBackend.wireName + ": " + shortcut.exec,
                    null,
                    application,
                    null,
                    null,
                    null);
        }

        static Result builtIn(
                final String label,
                final BuiltInDesktopAppCatalog.Entry entry) {
            return new Result(
                    Kind.BUILT_IN,
                    label,
                    "MagicDesk",
                    null,
                    null,
                    entry,
                    null,
                    null);
        }

        static Result action(
                final String label,
                final Action action) {
            return new Result(
                    Kind.ACTION,
                    label,
                    "Action",
                    null,
                    null,
                    null,
                    action,
                    null);
        }

        static Result file(final ShellFileInfo file) {
            return new Result(
                    Kind.FILE,
                    file.name,
                    file.absolutePath,
                    null,
                    null,
                    null,
                    null,
                    file);
        }
    }

    interface Listener {
        void onResultsChanged();
    }

    private static final int MAX_FILE_RESULTS = 24;
    private static final long FILE_SEARCH_DEBOUNCE_MILLIS = 180L;

    private final Activity mContext;
    private final StartMenuScope mScope;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker;
    private final FileManagerSearchController mFileSearch;
    private final List<Result> mLocalResults = new ArrayList<>();
    private final List<Result> mFileResults = new ArrayList<>();
    private final Set<String> mDesktopApplicationPaths =
            new LinkedHashSet<>();
    private final Runnable mStartFileSearch = this::startFileSearch;

    private String mQuery = "";
    private boolean mClosed;

    StartSearchController(
            final Activity context,
            final StartMenuScope scope,
            final Listener listener) {
        mContext = context;
        mScope = scope;
        mListener = listener;
        if (scope == StartMenuScope.PHONE) {
            mWorker = null;
            mFileSearch = null;
            return;
        }
        mWorker =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskStartSearch");
                thread.setDaemon(true);
                return thread;
            });
        mFileSearch = new FileManagerSearchController(
                context,
                mWorker,
                new FileManagerSearchController.Listener() {
                    @Override
                    public void onSearchBatch(
                            final List<ShellFileInfo> matches) {
                        if (mClosed || matches == null) {
                            return;
                        }
                        for (final ShellFileInfo match : matches) {
                            if (!mDesktopApplicationPaths.contains(
                                    match.absolutePath)) {
                                mFileResults.add(Result.file(match));
                            }
                        }
                        sortFileResults();
                        mListener.onResultsChanged();
                    }

                    @Override
                    public void onSearchFinished(
                            final boolean successful,
                            final boolean truncated,
                            final String message) {
                        if (!mClosed) {
                            mListener.onResultsChanged();
                        }
                    }

                    @Override
                    public void onSearchStartFailed(
                            final Throwable error) {
                        if (!mClosed) {
                            mListener.onResultsChanged();
                        }
                    }
                });
    }

    void update(
            final String query,
            final List<AppItem> apps,
            final List<DesktopApplicationRepository.Entry> applications) {
        if (mClosed) {
            return;
        }
        mQuery = normalize(query);
        mLocalResults.clear();
        mFileResults.clear();
        mDesktopApplicationPaths.clear();
        mHandler.removeCallbacks(mStartFileSearch);
        if (mFileSearch != null) {
            mFileSearch.cancel();
        }
        if (mQuery.isEmpty()) {
            mListener.onResultsChanged();
            return;
        }
        collectApps(apps);
        if (mScope == StartMenuScope.DESKTOP) {
            collectDesktopApplications(applications);
            collectActions();
        }
        sortLocalResults();
        mListener.onResultsChanged();
        if (mFileSearch != null && mQuery.length() >= 2 && ShellAccess.isReady()) {
            mHandler.postDelayed(
                    mStartFileSearch,
                    FILE_SEARCH_DEBOUNCE_MILLIS);
        }
    }

    List<Result> results(final int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        final List<Result> result = new ArrayList<>(Math.min(
                limit, mLocalResults.size() + mFileResults.size()));
        for (final Result local : mLocalResults) {
            if (result.size() >= limit) {
                return result;
            }
            result.add(local);
        }
        for (final Result file : mFileResults) {
            if (result.size() >= limit) {
                break;
            }
            result.add(file);
        }
        return result;
    }

    void pause() {
        mHandler.removeCallbacks(mStartFileSearch);
        if (mFileSearch != null) {
            mFileSearch.cancel();
        }
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        mHandler.removeCallbacks(mStartFileSearch);
        if (mFileSearch != null) {
            mFileSearch.close();
            mWorker.shutdownNow();
        }
    }

    private void collectApps(final List<AppItem> apps) {
        final Set<AppLaunchTarget> targets = new LinkedHashSet<>();
        if (apps != null) {
            for (final AppItem app : apps) {
                if (matches(app.label, app.packageName)) {
                    mLocalResults.add(Result.app(app));
                    targets.add(app.launchTarget);
                }
            }
        }
        if (mScope == StartMenuScope.PHONE) {
            return;
        }
        for (final BuiltInDesktopAppCatalog.Entry entry
                : BuiltInDesktopAppCatalog.searchEntries()) {
            if (targets.contains(entry.launchTarget)) {
                continue;
            }
            final String label = mContext.getString(entry.fallbackLabelResId);
            if (matches(label, "magicdesk")) {
                mLocalResults.add(Result.builtIn(label, entry));
            }
        }
    }

    private void collectDesktopApplications(
            final List<DesktopApplicationRepository.Entry> applications) {
        if (applications == null) {
            return;
        }
        for (final DesktopApplicationRepository.Entry application
                : applications) {
            mDesktopApplicationPaths.add(application.desktopFilePath);
            final DesktopApplicationShortcut shortcut = application.shortcut;
            if (shortcut.hasExecLaunch()
                    && matches(shortcut.name, shortcut.exec)) {
                mLocalResults.add(Result.desktopApplication(application));
            }
        }
    }

    private void collectActions() {
        addAction(R.string.action_show_desktop, Action.SHOW_DESKTOP, "windows home");
        addAction(R.string.action_screenshot, Action.SCREENSHOT, "capture print screen");
        addAction(
                R.string.action_record_screen,
                Action.SCREEN_RECORDING,
                "capture video stop recording");
    }

    private void addAction(
            final int labelResId,
            final Action action,
            final String keywords) {
        final String label = mContext.getString(labelResId);
        if (matches(label, keywords)) {
            mLocalResults.add(Result.action(label, action));
        }
    }

    private boolean matches(final String label, final String keywords) {
        return normalize(label).contains(mQuery)
                || normalize(keywords).contains(mQuery);
    }

    private void sortLocalResults() {
        mLocalResults.sort(Comparator
                .comparingInt((Result result) -> rank(result.label))
                .thenComparingInt(result -> result.kind.ordinal())
                .thenComparing(result -> result.label, String.CASE_INSENSITIVE_ORDER));
    }

    private void sortFileResults() {
        mFileResults.sort(Comparator
                .comparingInt((Result result) -> rank(result.label))
                .thenComparing(result -> result.label, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(result -> result.detail));
    }

    private int rank(final String label) {
        final String normalized = normalize(label);
        if (normalized.equals(mQuery)) {
            return 0;
        }
        if (normalized.startsWith(mQuery)) {
            return 1;
        }
        return 2;
    }

    private void startFileSearch() {
        if (!mClosed && mFileSearch != null
                && mQuery.length() >= 2 && ShellAccess.isReady()) {
            mFileSearch.start(
                    ShellDesktopDirectory.ABSOLUTE_PATH,
                    mQuery,
                    false,
                    MAX_FILE_RESULTS);
        }
    }

    private static String normalize(final String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
