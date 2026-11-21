package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import java.util.Arrays;
import java.util.List;

/** Native choices retain their values and selection; both presentations share the host palette. */
class UiChoiceAdapter<T> extends ArrayAdapter<T> {
    UiChoiceAdapter(Context context, List<T> values) {
        super(context, android.R.layout.simple_spinner_item, values);
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    static <T> void bind(Spinner spinner, T[] values) { bind(spinner, Arrays.asList(values)); }

    static <T> void bind(Spinner spinner, List<T> values) {
        bindAdapter(spinner,new UiChoiceAdapter<>(spinner.getContext(),values));
    }

    static void bindAdapter(Spinner spinner, UiChoiceAdapter<?> adapter) {
        UiAppearance.backgroundTint(spinner, UiColor.MUTED);
        spinner.setPopupBackgroundDrawable(UiAppearance.paint(spinner.getContext(), UiColor.PANEL,
                UiToolLayout.dp(spinner.getContext(), 8), UiColor.HOVER));
        spinner.setAdapter(adapter);
    }

    @Override public View getView(int position, View convert, ViewGroup parent) {
        View view = super.getView(position, convert, parent);
        UiAppearance.contents(view);
        return view;
    }

    @Override public View getDropDownView(int position, View convert, ViewGroup parent) {
        View view = super.getDropDownView(position, convert, parent);
        UiAppearance.contents(view);
        UiAppearance.background(view, UiColor.PANEL);
        return view;
    }
}
