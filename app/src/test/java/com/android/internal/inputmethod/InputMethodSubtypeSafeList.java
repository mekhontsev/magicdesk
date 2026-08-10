package com.android.internal.inputmethod;

import android.view.inputmethod.InputMethodSubtype;

import java.util.List;

public final class InputMethodSubtypeSafeList {
    private final List<InputMethodSubtype> mSubtypes;

    public InputMethodSubtypeSafeList(
            final List<InputMethodSubtype> subtypes) {
        mSubtypes = subtypes;
    }

    public static List<InputMethodSubtype> extractFrom(
            final InputMethodSubtypeSafeList safeList) {
        return safeList.mSubtypes;
    }
}
