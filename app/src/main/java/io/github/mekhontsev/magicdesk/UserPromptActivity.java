package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** An ordinary Android dialog Activity. Desktop placement remains owned by ToolApplications. */
public final class UserPromptActivity extends Activity {
    private UserInteractions owner;
    private String id;
    private EditText input;
    private final HashSet<String> selected = new HashSet<>();
    private CompletableFuture<Void> completion;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        owner = UserInteractions.current();
        id = getIntent().getStringExtra(UserInteractions.EXTRA_ID);
        final UserInteractionRequest request = owner == null ? null : owner.registry.pending(id);
        if (request == null || !request.kind().equals("dialog")) {
            owner = null;
            finishAndRemoveTask();
            return;
        }
        setTitle(R.string.script_dialog);
        setFinishOnTouchOutside(true);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::finishAndRemoveTask);
        if (state != null && state.getStringArrayList("selection") != null) {
            selected.addAll(state.getStringArrayList("selection"));
        }

        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(16), dp(24), dp(16));
        final TextView title = new TextView(this);
        title.setText(request.title()); title.setTextSize(20);
        content.addView(title);
        if (!request.message().isEmpty()) {
            final TextView message = new TextView(this);
            message.setText(request.message()); message.setTextSize(16);
            message.setPadding(0, dp(12), 0, dp(12));
            content.addView(message);
        }
        if (request.type().equals("text")) {
            input = new EditText(this);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(8192)});
            input.setMinLines(2); input.setMaxLines(6);
            input.setContentDescription(request.title());
            input.setText(state == null ? request.initialText() : state.getString("draft", request.initialText()));
            content.addView(input, new LinearLayout.LayoutParams(-1, -2));
        }
        final LinearLayout buttons = new LinearLayout(this);
        buttons.setGravity(Gravity.END);
        final Button cancel = new Button(this);
        cancel.setText(android.R.string.cancel);
        cancel.setOnClickListener(view -> finishAndRemoveTask());
        final Button accept = new Button(this);
        accept.setText(android.R.string.ok);
        accept.setEnabled(!request.type().equals("choice") || request.multiple() || !selected.isEmpty());
        accept.setOnClickListener(view -> answer(request));

        if (request.type().equals("choice")) {
            final RadioGroup radios = new RadioGroup(this);
            radios.setOrientation(LinearLayout.VERTICAL);
            for (UserInteractionRequest.Item item : request.items()) {
                if (request.multiple()) {
                    final CheckBox choice = new CheckBox(this);
                    choice.setText(item.label()); choice.setChecked(selected.contains(item.id()));
                    choice.setOnCheckedChangeListener((view, checked) -> {
                        if (checked) selected.add(item.id()); else selected.remove(item.id());
                    });
                    content.addView(choice);
                } else {
                    final RadioButton choice = new RadioButton(this);
                    choice.setId(android.view.View.generateViewId());
                    choice.setText(item.label()); choice.setChecked(selected.contains(item.id()));
                    choice.setOnCheckedChangeListener((view, checked) -> {
                        if (checked) { selected.clear(); selected.add(item.id()); accept.setEnabled(true); }
                    });
                    radios.addView(choice);
                }
            }
            if (!request.multiple()) content.addView(radios);
        }
        buttons.addView(cancel); buttons.addView(accept);
        content.addView(buttons);
        final ScrollView scroll = new ScrollView(this);
        scroll.addView(content, new ViewGroup.LayoutParams(-1, -2));
        setContentView(scroll);
        completion = owner.registry.whenFinished(id, () -> runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) finishAndRemoveTask();
        }));
    }

    private void answer(UserInteractionRequest request) {
        try {
            final JSONObject result = new JSONObject();
            switch (request.type()) {
                case "text" -> result.put("text", input.getText().toString());
                case "confirm" -> result.put("confirmed", true);
                case "choice" -> {
                    final JSONArray values = new JSONArray();
                    for (UserInteractionRequest.Item item : request.items()) {
                        if (selected.contains(item.id())) values.put(item.id());
                    }
                    result.put("selectedIds", values);
                }
                default -> throw new IllegalStateException("unknown dialog type");
            }
            owner.registry.complete(id, "completed", result, "");
        } catch (JSONException error) {
            owner.registry.complete(id, "failed", null, error.getMessage());
        }
    }

    @Override protected void onPostResume() {
        super.onPostResume();
        if (owner != null) owner.registry.presented(id);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (input != null) state.putString("draft", input.getText().toString());
        state.putStringArrayList("selection", new ArrayList<>(selected));
    }

    @Override protected void onDestroy() {
        if (completion != null) completion.cancel(false);
        if (owner != null && !isChangingConfigurations()) owner.registry.complete(id, "cancelled", null, "");
        super.onDestroy();
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
