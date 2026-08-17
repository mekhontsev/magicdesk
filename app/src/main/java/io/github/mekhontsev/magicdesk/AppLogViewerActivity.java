package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class AppLogViewerActivity extends Activity
        implements ShellAccess.StateListener {
    private static final String EXTRA_PACKAGE =
            "io.github.mekhontsev.magicdesk.extra.LOG_PACKAGE";
    private static final String EXTRA_LABEL =
            "io.github.mekhontsev.magicdesk.extra.LOG_LABEL";
    private static final int MAX_TRANSCRIPT_CHARS = 500_000;
    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_PANEL = 0xFF172033;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_CYAN = 0xFF22D3EE;

    private final ExecutorService mWorker =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskAppLog");
                thread.setDaemon(true);
                return thread;
            });
    private final Object mPendingLock = new Object();
    private final StringBuilder mPending = new StringBuilder();
    private final AtomicInteger mStreamGeneration = new AtomicInteger();

    private EditText mOutput;
    private TextView mStatus;
    private ImageButton mToggle;
    private volatile ShellStreamHandle mStream;
    private String mPackageName;
    private String mLabel;
    private boolean mDrainScheduled;
    private volatile boolean mStarting;
    private volatile boolean mDestroyed;

    static Intent createIntent(
            final Context context,
            final String packageName,
            final String label) {
        return new Intent(context, AppLogViewerActivity.class)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_LABEL, label);
    }

    static AppLaunchTarget launchTarget() {
        return BuiltInDesktopAppCatalog.logViewerTarget();
    }

    @Override
    protected void onCreate(final Bundle state) {
        super.onCreate(state);
        mPackageName = getIntent().getStringExtra(EXTRA_PACKAGE);
        mLabel = getIntent().getStringExtra(EXTRA_LABEL);
        if (!PackageNameValidator.isSafe(mPackageName)) {
            finish();
            return;
        }
        if (mLabel == null || mLabel.isEmpty()) {
            mLabel = mPackageName;
        }
        setTaskDescription(new ActivityManager.TaskDescription.Builder()
                .setLabel(getString(R.string.app_logs_title, mLabel))
                .setIcon(R.drawable.ic_magicdesk)
                .build());
        BuiltInWindowRegistry.register(this);
        setContentView(createContent());
        startStream();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ShellAccess.addStateListener(this);
    }

    @Override
    protected void onStop() {
        ShellAccess.removeStateListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        closeStream();
        mWorker.shutdownNow();
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }

    @Override
    public void onShellStateChanged(final ShellAccess.Snapshot snapshot) {
        runOnUiThread(() -> {
            if (mDestroyed) {
                return;
            }
            if (snapshot != null && snapshot.isReady()) {
                if (mStream == null && !mStarting) {
                    startStream();
                }
            } else {
                closeStream();
                mStatus.setText(getString(
                        R.string.app_logs_unavailable,
                        snapshot == null ? "unknown" : snapshot.error));
            }
        });
    }

    private View createContent() {
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(10), dp(8), dp(10), dp(8));
        page.setBackgroundColor(COLOR_BACKGROUND);
        SystemBarInsets.addToPadding(page);

        final LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final TextView title = new TextView(this);
        title.setText(getString(R.string.app_logs_title, mLabel));
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(17f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mToggle = iconButton(
                android.R.drawable.ic_media_pause,
                R.string.app_logs_stop,
                view -> toggleStream());
        header.addView(mToggle, square());
        header.addView(iconButton(
                android.R.drawable.ic_menu_delete,
                R.string.console_clear,
                view -> mOutput.setText("")), square());
        header.addView(iconButton(
                R.drawable.ic_file_copy,
                R.string.console_copy_output,
                view -> copyOutput()), square());
        page.addView(header);

        mStatus = new TextView(this);
        mStatus.setTextColor(COLOR_CYAN);
        mStatus.setTextSize(12f);
        mStatus.setTypeface(Typeface.MONOSPACE);
        page.addView(mStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(28)));

        mOutput = new EditText(this);
        mOutput.setTextColor(COLOR_TEXT);
        mOutput.setTextSize(11f);
        mOutput.setTypeface(Typeface.MONOSPACE);
        mOutput.setGravity(Gravity.TOP | Gravity.START);
        mOutput.setKeyListener(null);
        mOutput.setTextIsSelectable(true);
        mOutput.setCursorVisible(false);
        mOutput.setShowSoftInputOnFocus(false);
        mOutput.setPadding(dp(8), dp(6), dp(8), dp(6));
        mOutput.setBackground(rounded(COLOR_PANEL, dp(6)));
        page.addView(mOutput, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return page;
    }

    private void toggleStream() {
        if (mStream != null || mStarting) {
            closeStream();
            mStatus.setText(R.string.app_logs_stopped);
        } else {
            startStream();
        }
    }

    private void startStream() {
        if (mDestroyed || mStarting || mStream != null
                || !ShellAccess.isReady()) {
            return;
        }
        mStarting = true;
        final int generation = mStreamGeneration.incrementAndGet();
        mStatus.setText(R.string.app_logs_starting);
        mWorker.execute(() -> {
            try {
                final int uid = packageUid();
                final ShellStreamHandle stream = ShellAccess.openOwnedStream(
                        "exec /system/bin/logcat --uid=" + uid
                                + " -v threadtime");
                if (mDestroyed
                        || generation != mStreamGeneration.get()) {
                    stream.close();
                    return;
                }
                mStream = stream;
                mStarting = false;
                runOnUiThread(() -> {
                    if (mDestroyed) {
                        stream.close();
                        return;
                    }
                    updateToggle();
                    mStatus.setText(getString(
                            R.string.app_logs_streaming,
                            mPackageName,
                            uid));
                });
                readStream(stream);
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (generation != mStreamGeneration.get()) {
                        return;
                    }
                    mStarting = false;
                    updateToggle();
                    if (!mDestroyed) {
                        mStatus.setText(getString(
                                R.string.app_logs_unavailable,
                                ShellAccess.usefulMessage(error)));
                    }
                });
            }
        });
    }

    private int packageUid() throws IOException {
        try {
            final ApplicationInfo info = getPackageManager()
                    .getApplicationInfo(mPackageName, 0);
            return info.uid;
        } catch (PackageManager.NameNotFoundException error) {
            throw new IOException("package is no longer installed", error);
        }
    }

    private void readStream(final ShellStreamHandle stream)
            throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        stream.inputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (!mDestroyed
                    && stream == mStream
                    && (line = reader.readLine()) != null) {
                enqueue(line + "\n");
            }
        } catch (IOException error) {
            if (!mDestroyed && stream == mStream) {
                throw error;
            }
        } finally {
            runOnUiThread(() -> {
                if (mStream == stream) {
                    mStream = null;
                    mStarting = false;
                    updateToggle();
                    if (!mDestroyed) {
                        mStatus.setText(R.string.app_logs_stopped);
                    }
                }
            });
        }
    }

    private void enqueue(final String text) {
        synchronized (mPendingLock) {
            mPending.append(text);
            if (mDrainScheduled) {
                return;
            }
            mDrainScheduled = true;
        }
        mOutput.post(this::drainPending);
    }

    private void drainPending() {
        final String text;
        synchronized (mPendingLock) {
            text = mPending.toString();
            mPending.setLength(0);
            mDrainScheduled = false;
        }
        if (mDestroyed || text.isEmpty()) {
            return;
        }
        mOutput.append(text);
        final int excess = mOutput.length() - MAX_TRANSCRIPT_CHARS;
        if (excess > 0) {
            final int newline = mOutput.getText().toString()
                    .indexOf('\n', excess);
            mOutput.getText().delete(0, newline < 0 ? excess : newline + 1);
        }
        mOutput.setSelection(mOutput.length());
    }

    private void closeStream() {
        mStreamGeneration.incrementAndGet();
        final ShellStreamHandle stream = mStream;
        mStream = null;
        mStarting = false;
        if (stream != null) {
            stream.close();
        }
        updateToggle();
    }

    private void updateToggle() {
        if (mToggle == null) {
            return;
        }
        final boolean running = mStream != null || mStarting;
        mToggle.setImageResource(running
                ? android.R.drawable.ic_media_pause
                : android.R.drawable.ic_media_play);
        mToggle.setContentDescription(getString(running
                ? R.string.app_logs_stop : R.string.app_logs_start));
        mToggle.setTooltipText(mToggle.getContentDescription());
    }

    private void copyOutput() {
        final ClipboardManager clipboard = getSystemService(
                ClipboardManager.class);
        if (clipboard == null) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(
                "MagicDesk app logs", mOutput.getText()));
        Toast.makeText(this, R.string.console_copied,
                Toast.LENGTH_SHORT).show();
    }

    private ImageButton iconButton(
            final int drawable,
            final int description,
            final View.OnClickListener listener) {
        final ImageButton button = new ImageButton(this);
        button.setImageResource(drawable);
        button.setImageTintList(new ColorStateList(
                new int[][]{new int[0]}, new int[]{COLOR_TEXT}));
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(getString(description));
        button.setTooltipText(getString(description));
        button.setOnClickListener(listener);
        return button;
    }

    private LinearLayout.LayoutParams square() {
        return new LinearLayout.LayoutParams(dp(44), dp(44));
    }

    private GradientDrawable rounded(final int color, final int radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
