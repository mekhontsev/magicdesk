package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Harmless UI fixture with no Desktop registry; exercises the actual Android accessibility path. */
public final class DebugUiAutomationActivity extends Activity {
    @Override protected void onCreate(final Bundle state) {
        super.onCreate(state);
        final ScrollView scroll = new ScrollView(this);
        scroll.setContentDescription("Automation scroll");
        final LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(24, 64, 24, 64);
        body.setBackgroundColor(Color.WHITE);
        scroll.addView(body);
        final TextView status = new TextView(this);
        status.setTextColor(Color.BLACK);
        status.setText("Ready");
        status.setContentDescription("Automation status");
        body.addView(status);
        final EditText editor = new EditText(this);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setContentDescription("Automation editor");
        body.addView(editor);
        final EditText password = new EditText(this);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText("fixture-secret");
        password.setContentDescription("Password fixture");
        body.addView(password);
        final Button button = new Button(this);
        button.setText("Test action");
        button.setOnClickListener(view -> status.setText("Clicked"));
        button.setOnLongClickListener(view -> { status.setText("Long clicked"); return true; });
        body.addView(button);
        final Button reuse = new Button(this);
        reuse.setText("Original identity");
        reuse.setOnClickListener(view -> reuse.setText("Changed identity"));
        body.addView(reuse);
        for (int i = 0; i < 30; i++) {
            final TextView row = new TextView(this);
            row.setText("Automation row " + i);
            row.setTextColor(Color.BLACK);
            row.setMinHeight(72);
            body.addView(row);
        }
        final Button close = new Button(this);
        close.setText("Close fixture");
        close.setOnClickListener(view -> finish());
        body.addView(close);
        setContentView(scroll);
        body.setFocusableInTouchMode(true);
        body.requestFocus();
        getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
            final var bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            body.setPadding(24 + bars.left, 24 + bars.top, 24 + bars.right, 24 + bars.bottom);
            return insets;
        });
    }
}
