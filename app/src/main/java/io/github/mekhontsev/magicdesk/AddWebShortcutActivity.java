package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Share target that writes a standard web Desktop Entry. */
public final class AddWebShortcutActivity extends Activity
        implements ShellAccess.StateListener {
    private final ExecutorService mWorker =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskWebShortcut");
                thread.setDaemon(true);
                return thread;
            });

    private WebShortcutShareRequest mRequest;
    private AlertDialog mDialog;
    private EditText mName;
    private TextView mStatus;
    private Button mAdd;
    private String mFailure = "";
    private boolean mSaving;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRequest = WebShortcutShareRequest.from(getIntent());
        if (mRequest == null) {
            Toast.makeText(
                    this,
                    R.string.share_web_shortcut_invalid,
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        showConfirmation();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ShellAccess.addStateListener(this);
        updateShellState(ShellAccess.currentSnapshot());
    }

    @Override
    protected void onStop() {
        ShellAccess.removeStateListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mWorker.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onShellStateChanged(final ShellAccess.Snapshot snapshot) {
        runOnUiThread(() -> updateShellState(snapshot));
    }

    private void showConfirmation() {
        final LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        final int horizontal = dp(24);
        form.setPadding(horizontal, dp(4), horizontal, 0);

        mName = new EditText(this);
        mName.setHint(R.string.share_web_shortcut_name);
        mName.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        mName.setSingleLine(true);
        mName.setText(mRequest.name);
        mName.setSelectAllOnFocus(true);
        form.addView(mName, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView url = new TextView(this);
        url.setText(mRequest.url);
        url.setTextIsSelectable(true);
        url.setPadding(0, dp(12), 0, 0);
        form.addView(url, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        mStatus = new TextView(this);
        mStatus.setPadding(0, dp(12), 0, 0);
        form.addView(mStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        mDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.share_web_shortcut_title)
                .setView(form)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.share_web_shortcut_add, null)
                .create();
        mDialog.setOnDismissListener(dialog -> {
            if (!isFinishing()) {
                finish();
            }
        });
        mDialog.setOnShowListener(dialog -> {
            mAdd = mDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            mAdd.setOnClickListener(view -> save());
            updateShellState(ShellAccess.currentSnapshot());
        });
        mDialog.show();
    }

    private void save() {
        final String name = mName.getText().toString().trim();
        if (name.isEmpty()) {
            mName.setError(getString(R.string.desktop_entry_name_required));
            return;
        }
        mFailure = "";
        mSaving = true;
        updateShellState(ShellAccess.currentSnapshot());
        mWorker.execute(() -> {
            try {
                DesktopEntryFile.createWebLink(name, mRequest.url);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    Toast.makeText(
                            this,
                            getString(
                                    R.string.share_web_shortcut_added,
                                    name),
                            Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (RuntimeException | java.io.IOException error) {
                runOnUiThread(() -> {
                    mSaving = false;
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    mFailure = getString(
                            R.string.share_web_shortcut_failed,
                            ShellAccess.usefulMessage(error));
                    updateShellState(ShellAccess.currentSnapshot());
                });
            }
        });
    }

    private void updateShellState(final ShellAccess.Snapshot snapshot) {
        if (mStatus == null) {
            return;
        }
        final boolean ready = snapshot != null && snapshot.isReady();
        if (!mSaving) {
            mStatus.setText(!mFailure.isEmpty()
                    ? mFailure
                    : ready ? ""
                    : getString(
                            R.string.share_web_shortcut_shell_unavailable,
                            ShellAccess.statusLabel()));
        }
        if (mAdd != null) {
            mAdd.setEnabled(ready && !mSaving);
            mDialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setEnabled(!mSaving);
        }
        mName.setEnabled(!mSaving);
    }

    private int dp(final int value) {
        return Math.round(value * getResources()
                .getDisplayMetrics().density);
    }
}
