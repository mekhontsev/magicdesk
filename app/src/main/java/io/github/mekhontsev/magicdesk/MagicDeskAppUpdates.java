package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.ParcelFileDescriptor;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.File;
import java.io.FileInputStream;
import java.util.Set;

/** Durable operation receipts, independent of sockets and of the process being replaced. */
final class MagicDeskAppUpdates {
    private static final String STORE = "mcp-app-updates";

    private MagicDeskAppUpdates() { }

    static synchronized JSONObject start(Context context, JSONObject args) throws IOException, JSONException {
        final String id = AutomationFileTransfers.identifier(args.getString("updateId"));
        final String sha = AutomationFileTransfers.digestArgument(args.getString("sha256"));
        final JSONObject previous = status(context, id);
        if (!previous.getString("state").equals("unknown")) {
            if (!sha.equals(previous.optString("sha256"))) throw new IllegalArgumentException("updateId belongs to another APK");
            return previous;
        }
        final DesktopSessionSnapshot desktop = DesktopRuntimeBridge.getSessionSnapshot();
        if (desktop.target() != null || desktop.hasHost() || DesktopSelfTestRunState.isActive()) {
            throw new IllegalArgumentException("close desktop and finish self-test cleanup before updating MagicDesk");
        }
        final SharedPreferences preferences = preferences(context);
        for (String previousId : preferences.getAll().keySet()) {
            if (Set.of("preparing", "submitted", "submission_unknown")
                    .contains(status(context, previousId).getString("state"))) {
                throw new IllegalStateException("another update is pending: " + previousId);
            }
        }
        prune(context, preferences);
        final JSONObject receipt = new JSONObject().put("updateId", id).put("sha256", sha)
                .put("state", "preparing").put("createdAt", System.currentTimeMillis());
        save(preferences, receipt);
        int stagedSession = -1;
        final int userId = AppProfile.current(context).userId;
        boolean handedOff = false;
        try {
            final ShellFileInfo apk = ShellAccess.getShellFileInfo(args.getString("path"));
            if (!apk.isRegularFile() || apk.symbolicLink) throw new IllegalArgumentException("ordinary APK file required");
            final JSONObject staged = new JSONObject(ShellAccess.prepareMagicDeskUpdate(apk, sha, userId));
            final int sessionId = staged.getInt("sessionId");
            stagedSession = sessionId;
            receipt.put("sessionId", sessionId).put("versionCode", staged.getLong("versionCode"))
                    .put("versionName", staged.getString("versionName")).put("state", "submitted");
            save(preferences, receipt);
            try (ParcelFileDescriptor output = ParcelFileDescriptor.open(resultFile(context, id),
                    ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE
                            | ParcelFileDescriptor.MODE_WRITE_ONLY)) {
                AppUpdateWorkerConnection.begin(context, sessionId, userId, id, output);
                handedOff = true;
            }
        } catch (IOException | RuntimeException error) {
            // A Binder disconnect during commit is not evidence that installation failed.
            final boolean uncertain = handedOff || error instanceof AppUpdateWorkerConnection.HandoffException handoff
                    && handoff.mayHaveStarted;
            if (!uncertain && stagedSession >= 0) {
                try { ShellAccess.abandonMagicDeskUpdate(stagedSession, userId); }
                catch (IOException cleanup) { error.addSuppressed(cleanup); }
            }
            receipt.put("state", uncertain ? "submission_unknown" : "failed")
                    .put("detail", ShellAccess.usefulMessage(error));
            save(preferences, receipt);
        }
        return status(context, id);
    }

    static synchronized JSONObject status(Context context, String id) throws JSONException {
        AutomationFileTransfers.identifier(id);
        final String saved = preferences(context).getString(id, null);
        if (saved == null) return new JSONObject().put("updateId", id).put("state", "unknown");
        final JSONObject receipt = new JSONObject(saved);
        final File file = resultFile(context, id);
        if (file.isFile()) {
            try (var in = new FileInputStream(file)) {
                final JSONObject result = AppUpdateReceipt.read(in);
                if (result != null) AppUpdateReceipt.merge(receipt, result);
            } catch (IOException | JSONException error) {
                // A damaged worker receipt is not evidence about the installer outcome.
                receipt.put("receiptError", error.getMessage());
            }
        }
        return receipt;
    }

    private static File resultFile(Context context, String id) {
        return new File(context.getFilesDir(), "app-update-" + id + ".result");
    }

    private static SharedPreferences preferences(Context context) { return context.getSharedPreferences(STORE, Context.MODE_PRIVATE); }

    private static void save(SharedPreferences preferences, JSONObject receipt) throws IOException, JSONException {
        if (!preferences.edit().putString(receipt.getString("updateId"), receipt.toString()).commit()) {
            throw new IOException("cannot persist update receipt");
        }
    }

    private static void prune(Context context, SharedPreferences preferences) throws JSONException, IOException {
        int count = preferences.getAll().size();
        for (var entry : preferences.getAll().entrySet()) {
            if (count < 16) return;
            if (Set.of("installed", "failed", "user_action_required").contains(status(context, entry.getKey()).optString("state"))) {
                if (!preferences.edit().remove(entry.getKey()).commit()) throw new IOException("cannot prune update receipts");
                final File file = resultFile(context, entry.getKey());
                if (file.exists() && !file.delete()) throw new IOException("cannot prune worker receipt");
                count--;
            }
        }
        if (count >= 16) throw new IOException("too many unfinished update requests");
    }
}
