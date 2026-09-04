package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Developer surface for resolving and launching Activities through production policy. */
public final class ActivityExplorerActivity extends Activity {
    private final ExecutorService mWorker =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskActivityExplorer");
                thread.setDaemon(true);
                return thread;
            });

    private EditText mAction;
    private EditText mData;
    private EditText mMime;
    private EditText mPackage;
    private Spinner mMode;
    private Spinner mInstance;
    private CheckBox mExpectResult;
    private TextView mStatus;
    private LinearLayout mResults;
    private volatile boolean mDestroyed;

    static Intent createIntent(final Context context) {
        return new Intent(context, ActivityExplorerActivity.class);
    }

    static AppLaunchTarget launchTarget() {
        return BuiltInDesktopAppCatalog.activityExplorerTarget();
    }

    @Override
    protected void onCreate(final Bundle state) {
        super.onCreate(state);
        DesktopTaskDescription.apply(
                this,
                R.string.activity_explorer_title,
                R.drawable.ic_magicdesk);
        BuiltInWindowRegistry.register(this);
        setContentView(createContent());
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mWorker.shutdownNow();
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }

    private View createContent() {
        final DesktopUiFactory ui = new DesktopUiFactory(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(14));
        root.setBackgroundColor(DesktopUiFactory.COLOR_BACKGROUND);

        final TextView title = ui.sectionTitle(
                R.string.activity_explorer_title);
        root.addView(title, matchWrap());

        mAction = input(getString(R.string.activity_explorer_action));
        mAction.setText(Intent.ACTION_VIEW);
        root.addView(mAction, matchWrap());
        mData = input(getString(R.string.activity_explorer_data));
        mData.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(mData, matchWrap());
        mMime = input(getString(R.string.activity_explorer_mime));
        root.addView(mMime, matchWrap());
        mPackage = input(getString(R.string.activity_explorer_package));
        root.addView(mPackage, matchWrap());

        final LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.HORIZONTAL);
        mMode = spinner(new String[] {"auto", "windowed", "fullscreen"});
        mInstance = spinner(new String[] {"reuse", "new"});
        options.addView(mMode, weighted());
        options.addView(mInstance, weighted());
        root.addView(options, matchWrap());

        mExpectResult = new CheckBox(this);
        mExpectResult.setText(R.string.activity_explorer_expect_result);
        mExpectResult.setTextColor(DesktopUiFactory.COLOR_TEXT);
        root.addView(mExpectResult, matchWrap());

        final LinearLayout commands = new LinearLayout(this);
        commands.setGravity(Gravity.END);
        final Button history = ui.actionButton(
                R.string.activity_explorer_history,
                DesktopUiFactory.COLOR_PANEL_ALT);
        history.setOnClickListener(view -> showHistory());
        commands.addView(history, wrapWrap());
        final Button query = ui.actionButton(
                R.string.activity_explorer_query,
                DesktopUiFactory.COLOR_CYAN);
        query.setOnClickListener(view -> query());
        commands.addView(query, wrapWrap());
        root.addView(commands, matchWrap());

        mStatus = new TextView(this);
        mStatus.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mStatus.setTextSize(13);
        root.addView(mStatus, matchWrap());

        final ScrollView scroll = new ScrollView(this);
        mResults = new LinearLayout(this);
        mResults.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(mResults, matchWrap());
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void query() {
        mStatus.setText(R.string.activity_explorer_resolving);
        mResults.removeAllViews();
        final JSONObject request = request(false, "");
        mWorker.execute(() -> {
            try {
                final DesktopAutomationResult result =
                        new AndroidIntegrationGateway(this)
                                .queryIntentHandlers(request);
                runOnUiThread(() -> renderHandlers(result));
            } catch (Exception error) {
                showError(error);
            }
        });
    }

    private void launch(final String component) {
        mStatus.setText(R.string.activity_explorer_launching);
        final JSONObject request = request(true, component);
        mWorker.execute(() -> {
            try {
                final DesktopAutomationResult result =
                        new AndroidIntegrationGateway(this)
                                .launchIntent(request);
                runOnUiThread(() -> {
                    final String requestId = result.data.optString(
                            "requestId", "");
                    mStatus.setText(requestId.isEmpty()
                            ? result.message
                            : result.message + "\nrequestId=" + requestId);
                });
            } catch (Exception error) {
                showError(error);
            }
        });
    }

    private JSONObject request(
            final boolean launch,
            final String component) {
        final JSONObject request = new JSONObject();
        try {
            final boolean expectResult = launch
                    && mExpectResult.isChecked();
            request.put("kind", "activity")
                    .put("action", text(mAction))
                    .put("dataUri", text(mData))
                    .put("mimeType", text(mMime))
                    .put("package", text(mPackage))
                    .put("mode", String.valueOf(mMode.getSelectedItem()))
                    .put("instance", expectResult
                            ? "new" : String.valueOf(
                                    mInstance.getSelectedItem()))
                    .put("expectResult", expectResult)
                    .put("name", "Activity Explorer")
                    .put("limit", 200);
            if (launch && component != null && !component.isEmpty()) {
                request.put("component", component);
            }
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return request;
    }

    private void renderHandlers(final DesktopAutomationResult result) {
        if (mDestroyed) {
            return;
        }
        if (!result.success) {
            mStatus.setText(result.message);
            return;
        }
        final JSONArray handlers = result.data.optJSONArray("handlers");
        final int count = handlers == null ? 0 : handlers.length();
        mStatus.setText(getString(
                R.string.activity_explorer_found, Integer.valueOf(count)));
        for (int index = 0; index < count; index++) {
            final JSONObject handler = handlers.optJSONObject(index);
            if (handler == null) {
                continue;
            }
            final String component = handler.optString("component", "");
            final Button row = new Button(this);
            row.setAllCaps(false);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setText(handler.optString("label", component)
                    + "\n" + component
                    + "\nresize=" + value(handler, "resizeMode")
                    + " launch=" + handler.optInt("launchMode", -1)
                    + " document=" + handler.optInt(
                            "documentLaunchMode", -1)
                    + " orientation=" + handler.optInt(
                            "screenOrientation", -1)
                    + " pip=" + value(
                            handler, "supportsPictureInPicture"));
            row.setEnabled(handler.optBoolean("launchAllowed", false));
            row.setOnClickListener(view -> launch(component));
            mResults.addView(row, matchWrap());
        }
    }

    private void showHistory() {
        mResults.removeAllViews();
        final JSONArray history = AndroidActivityCompatibilityHistory.snapshot(64);
        mStatus.setText(getString(
                R.string.activity_explorer_history_count,
                Integer.valueOf(history.length())));
        for (int index = history.length() - 1; index >= 0; index--) {
            final TextView row = new TextView(this);
            row.setTextColor(DesktopUiFactory.COLOR_TEXT);
            row.setTextSize(13);
            row.setText(String.valueOf(history.opt(index)));
            row.setTextIsSelectable(true);
            row.setPadding(0, 8, 0, 8);
            mResults.addView(row, matchWrap());
        }
    }

    private void showError(final Throwable error) {
        runOnUiThread(() -> {
            if (!mDestroyed) {
                mStatus.setText(ShellAccess.usefulMessage(error));
            }
        });
    }

    private EditText input(final String hint) {
        final EditText input = new EditText(this);
        input.setHint(hint);
        input.setSingleLine(true);
        return input;
    }

    private Spinner spinner(final String[] values) {
        final Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                values));
        return spinner;
    }

    private static String text(final EditText input) {
        return input.getText().toString().trim();
    }

    private static String value(
            final JSONObject object,
            final String name) {
        final Object value = object.opt(name);
        return value == null || value == JSONObject.NULL
                ? "unknown" : String.valueOf(value);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }
}
