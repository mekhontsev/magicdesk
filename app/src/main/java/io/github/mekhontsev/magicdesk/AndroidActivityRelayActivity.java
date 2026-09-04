package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** App lifecycle owner for Android Activity-result requests from MCP. */
public final class AndroidActivityRelayActivity extends Activity {
    private static final String EXTRA_RELAY_ID = "relay_id";
    private static final String EXTRA_RESULT_ID = "result_id";
    private static final String STATE_STARTED = "started";
    private static final int REQUEST_TARGET = 1;

    private boolean mResultFinished;

    static Intent createIntent(
            final Context context,
            final String relayId,
            final String resultId) {
        return new Intent(context, AndroidActivityRelayActivity.class)
                .putExtra(EXTRA_RELAY_ID, relayId)
                .putExtra(EXTRA_RESULT_ID, resultId == null ? "" : resultId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_STARTED, false)) {
            return;
        }
        final String resultId = getIntent().getStringExtra(EXTRA_RESULT_ID);
        try {
            final AndroidActivityRelayStore.Claim claim =
                    AndroidActivityRelayStore.claim(
                            getIntent().getStringExtra(EXTRA_RELAY_ID));
            if (claim.alreadyClaimed) {
                mResultFinished = true;
                finish();
                return;
            }
            if (claim.request == null) {
                throw new IllegalStateException(
                        "Activity relay request is unavailable");
            }
            final AndroidActivityRelayStore.Request request = claim.request;
            final Intent target = request.chooser
                    ? Intent.createChooser(
                            request.target,
                            request.chooserTitle.isEmpty()
                                    ? null : request.chooserTitle)
                    : new Intent(request.target);
            target.removeFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            if (resultId == null || resultId.isEmpty()) {
                startActivity(target);
                finish();
            } else {
                startActivityForResult(target, REQUEST_TARGET);
            }
        } catch (RuntimeException error) {
            if (resultId != null && !resultId.isEmpty()) {
                AndroidActivityResultStore.fail(resultId, error);
                mResultFinished = true;
            }
            DesktopAutomationEventJournal.record(
                    "android-integration",
                    "activity-relay-failed",
                    false,
                    ShellAccess.usefulMessage(error),
                    null);
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(final Bundle outState) {
        outState.putBoolean(STATE_STARTED, true);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onActivityResult(
            final int requestCode,
            final int resultCode,
            final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TARGET) {
            final String resultId = getIntent().getStringExtra(EXTRA_RESULT_ID);
            if (resultId != null && !resultId.isEmpty()) {
                AndroidActivityResultStore.complete(
                        resultId, resultCode, data);
                mResultFinished = true;
            }
            finish();
        }
    }

    @Override
    protected void onDestroy() {
        if (!mResultFinished
                && isFinishing()
                && !isChangingConfigurations()) {
            final String resultId = getIntent().getStringExtra(EXTRA_RESULT_ID);
            if (resultId != null && !resultId.isEmpty()) {
                AndroidActivityResultStore.fail(
                        resultId,
                        new IllegalStateException(
                                "Activity result request was closed"));
                mResultFinished = true;
            }
        }
        super.onDestroy();
    }
}
