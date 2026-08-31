package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit Android share target that materializes shared content on Desktop. */
public final class DesktopContentReceiverActivity extends Activity {
    private static final Object DIAGNOSTICS_LOCK = new Object();
    private static long sRequests;
    private static long sSavedItems;
    private static long sFailures;
    private static long sTruncatedRequests;

    private final ExecutorService mWorker =
            Executors.newSingleThreadExecutor(runnable ->
                    new Thread(runnable, "MagicDeskContentImport"));
    private AndroidContentPayload mContent;
    private boolean mImporting;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContent = AndroidContentPayload.fromSendIntent(getIntent());
        recordRequest(mContent.truncated);
        if (mContent.isEmpty()) {
            Toast.makeText(
                    this,
                    R.string.desktop_share_unsupported,
                    Toast.LENGTH_LONG).show();
            recordFailure();
            finish();
            return;
        }
        final int itemCount = mContent.hasUris()
                ? mContent.uriItems.size() : 1;
        new AlertDialog.Builder(this)
                .setTitle(R.string.desktop_share_save_title)
                .setMessage(getResources().getQuantityString(
                        R.plurals.desktop_share_save_message,
                        itemCount,
                        itemCount))
                .setNegativeButton(android.R.string.cancel,
                        (dialog, which) -> finish())
                .setPositiveButton(
                        R.string.desktop_share_save_action,
                        (dialog, which) -> importContent())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    static String runtimeDiagnostics() {
        synchronized (DIAGNOSTICS_LOCK) {
            return "requests=" + sRequests
                    + ", savedItems=" + sSavedItems
                    + ", failures=" + sFailures
                    + ", truncatedRequests=" + sTruncatedRequests;
        }
    }

    private void importContent() {
        if (mImporting) {
            return;
        }
        mImporting = true;
        if (!ShellAccess.isReady()) {
            Toast.makeText(
                    this,
                    R.string.desktop_share_shizuku_required,
                    Toast.LENGTH_LONG).show();
            recordFailure();
            startActivity(ControlActivity.createLaunchIntent(this));
            finish();
            return;
        }
        final AndroidContentPayload content = mContent;
        mWorker.execute(() -> {
            final DesktopFileRepository.ImportResult result;
            try {
                result = new DesktopFileRepository(this).importContent(
                        content, ShellDesktopDirectory.ABSOLUTE_PATH);
            } catch (IOException | RuntimeException error) {
                final int count = content.hasUris()
                        ? content.uriItems.size() : 1;
                onImportFinished(new DesktopFileRepository.ImportResult(
                        0, count, error));
                return;
            }
            onImportFinished(result);
        });
    }

    private void onImportFinished(
            final DesktopFileRepository.ImportResult result) {
        runOnUiThread(() -> {
            if (result.copied > 0) {
                recordSaved(result.copied);
                Toast.makeText(
                        this,
                        getResources().getQuantityString(
                                R.plurals.desktop_share_saved,
                                result.copied,
                                result.copied),
                        Toast.LENGTH_LONG).show();
            }
            if (result.failed > 0 || result.firstFailure != null) {
                recordFailure();
                Toast.makeText(
                        this,
                        getString(
                                R.string.desktop_share_failed,
                                result.firstFailure == null
                                        ? "unknown error"
                                        : ShellAccess.usefulMessage(
                                                result.firstFailure)),
                        Toast.LENGTH_LONG).show();
            }
            mWorker.shutdown();
            finish();
        });
    }

    private static void recordRequest(final boolean truncated) {
        synchronized (DIAGNOSTICS_LOCK) {
            sRequests++;
            if (truncated) {
                sTruncatedRequests++;
            }
        }
    }

    private static void recordSaved(final int count) {
        synchronized (DIAGNOSTICS_LOCK) {
            sSavedItems += count;
        }
    }

    private static void recordFailure() {
        synchronized (DIAGNOSTICS_LOCK) {
            sFailures++;
        }
    }
}
