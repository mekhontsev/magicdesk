package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.EnumMap;

/** Records the manual observations that cannot be inferred by self-test. */
public final class CompatibilityOnboardingActivity extends Activity {
    private Spinner mTarget;
    private final EnumMap<CompatibilityOnboardingStore.Check, Spinner>
            mStates = new EnumMap<>(CompatibilityOnboardingStore.Check.class);

    static Intent createIntent(final Context context) {
        return new Intent(context, CompatibilityOnboardingActivity.class);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(createContentView());
        load(CompatibilityOnboardingStore.load(this).target);
    }

    private View createContentView() {
        final ScrollView scroll = new ScrollView(this);
        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(16), dp(18), dp(20));
        SystemBarInsets.addToPadding(page);
        page.setBackgroundColor(0xFF090D14);
        scroll.addView(page);

        final TextView title = text(
                getString(R.string.onboarding_title), 22, 0xFFE5E7EB);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        page.addView(title);

        final TextView description = text(
                getString(R.string.onboarding_description), 13, 0xFF94A3B8);
        final LinearLayout.LayoutParams descriptionParams = params();
        descriptionParams.setMargins(0, dp(6), 0, dp(12));
        page.addView(description, descriptionParams);

        mTarget = spinner(targetLabels());
        mTarget.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            final AdapterView<?> parent,
                            final View view,
                            final int position,
                            final long id) {
                        load(CompatibilityOnboardingStore.Target.values()[position]);
                    }

                    @Override
                    public void onNothingSelected(
                            final AdapterView<?> parent) {
                    }
                });
        page.addView(mTarget, params());

        for (final CompatibilityOnboardingStore.Check check
                : CompatibilityOnboardingStore.Check.values()) {
            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            final TextView label = text(
                    getString(check.labelResId), 14, 0xFFE5E7EB);
            row.addView(label, new LinearLayout.LayoutParams(
                    0, dp(52), 1));
            final Spinner state = spinner(stateLabels());
            mStates.put(check, state);
            row.addView(state, new LinearLayout.LayoutParams(
                    dp(150), dp(52)));
            page.addView(row, params());
        }

        final Button save = new Button(this);
        save.setText(R.string.onboarding_save);
        save.setOnClickListener(view -> save());
        final LinearLayout.LayoutParams saveParams = params();
        saveParams.setMargins(0, dp(12), 0, 0);
        page.addView(save, saveParams);
        return scroll;
    }

    private void load(final CompatibilityOnboardingStore.Target target) {
        if (mTarget.getSelectedItemPosition() != target.ordinal()) {
            mTarget.setSelection(target.ordinal());
        }
        final CompatibilityOnboardingStore.Record record =
                CompatibilityOnboardingStore.load(this, target);
        for (final CompatibilityOnboardingStore.Check check
                : CompatibilityOnboardingStore.Check.values()) {
            final Spinner state = mStates.get(check);
            if (state != null) {
                state.setSelection(record.states.get(check).ordinal());
            }
        }
    }

    private void save() {
        final CompatibilityOnboardingStore.Target target =
                CompatibilityOnboardingStore.Target.values()[
                        mTarget.getSelectedItemPosition()];
        final EnumMap<CompatibilityOnboardingStore.Check,
                CompatibilityOnboardingStore.State> states =
                new EnumMap<>(CompatibilityOnboardingStore.Check.class);
        for (final CompatibilityOnboardingStore.Check check
                : CompatibilityOnboardingStore.Check.values()) {
            states.put(
                    check,
                    CompatibilityOnboardingStore.State.values()[
                            mStates.get(check).getSelectedItemPosition()]);
        }
        CompatibilityOnboardingStore.save(
                this,
                new CompatibilityOnboardingStore.Record(
                        PlatformDevice.current().fingerprint,
                        target,
                        states));
        Toast.makeText(this, R.string.onboarding_saved, Toast.LENGTH_SHORT)
                .show();
        finish();
    }

    private Spinner spinner(final String[] labels) {
        final Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                labels));
        return spinner;
    }

    private String[] targetLabels() {
        return new String[] {
            getString(R.string.onboarding_target_phone),
            getString(R.string.onboarding_target_simulated),
            getString(R.string.onboarding_target_wired),
            getString(R.string.onboarding_target_wireless)
        };
    }

    private String[] stateLabels() {
        return new String[] {
            getString(R.string.onboarding_state_not_tested),
            getString(R.string.onboarding_state_pass),
            getString(R.string.onboarding_state_fail),
            getString(R.string.onboarding_state_unavailable)
        };
    }

    private TextView text(
            final String value,
            final int size,
            final int color) {
        final TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        return text;
    }

    private LinearLayout.LayoutParams params() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
