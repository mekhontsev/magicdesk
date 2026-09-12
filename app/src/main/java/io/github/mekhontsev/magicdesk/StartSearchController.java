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
    private final List<StartMenuEntry> mLocalResults = new ArrayList<>();
    private final List<StartMenuEntry> mFileResults = new ArrayList<>();
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
        if (scope == StartMenuScope.APPLICATIONS) {
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
                                mFileResults.add(StartMenuEntry.file(match));
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
            final List<StartMenuEntry> entries) {
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
        collectEntries(entries);
        if (mScope == StartMenuScope.DESKTOP) {
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

    List<StartMenuEntry> results(final int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        final List<StartMenuEntry> result = new ArrayList<>(Math.min(
                limit, mLocalResults.size() + mFileResults.size()));
        for (final StartMenuEntry local : mLocalResults) {
            if (result.size() >= limit) {
                return result;
            }
            result.add(local);
        }
        for (final StartMenuEntry file : mFileResults) {
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

    private void collectEntries(final List<StartMenuEntry> entries) {
        final Set<AppLaunchTarget> targets = new LinkedHashSet<>();
        for (final StartMenuEntry entry : entries) {
            if (matches(entry.label, entry.detail)) {
                mLocalResults.add(entry);
            }
            if (entry.app != null) { targets.add(entry.app.launchTarget); }
            if (entry.desktopApplication != null) {
                mDesktopApplicationPaths.add(entry.desktopApplication.desktopFilePath);
            }
        }
        if (mScope == StartMenuScope.APPLICATIONS) {
            return;
        }
        for (final BuiltInDesktopAppCatalog.Entry entry
                : BuiltInDesktopAppCatalog.searchEntries()) {
            if (targets.contains(entry.launchTarget)) {
                continue;
            }
            final String label = mContext.getString(entry.fallbackLabelResId);
            if (matches(label, "magicdesk")) {
                mLocalResults.add(StartMenuEntry.builtIn(label, entry));
            }
        }
    }

    private void collectActions() {
        addAction(R.string.action_show_desktop, StartMenuEntry.Action.SHOW_DESKTOP, "windows home");
        addAction(R.string.action_screenshot, StartMenuEntry.Action.SCREENSHOT, "capture print screen");
        addAction(
                R.string.action_record_screen,
                StartMenuEntry.Action.SCREEN_RECORDING,
                "capture video stop recording");
    }

    private void addAction(
            final int labelResId,
            final StartMenuEntry.Action action,
            final String keywords) {
        final String label = mContext.getString(labelResId);
        if (matches(label, keywords)) {
            mLocalResults.add(StartMenuEntry.action(label, action));
        }
    }

    private boolean matches(final String label, final String keywords) {
        return normalize(label).contains(mQuery)
                || normalize(keywords).contains(mQuery);
    }

    private void sortLocalResults() {
        mLocalResults.sort(Comparator
                .comparingInt((StartMenuEntry result) -> rank(result.label))
                .thenComparingInt(result -> result.kind.ordinal())
                .thenComparing(result -> result.label, String.CASE_INSENSITIVE_ORDER));
    }

    private void sortFileResults() {
        mFileResults.sort(Comparator
                .comparingInt((StartMenuEntry result) -> rank(result.label))
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
